package com.volmit.brimstone.integration;

import art.arcane.volmlib.integration.IntegrationMetricDescriptor;
import art.arcane.volmlib.integration.IntegrationMetricType;
import com.volmit.brimstone.diagnostics.EffectDiagnostics;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

final class BrimstoneIntegrationMetrics {
    private static final Map<String, Metric> METRICS = catalog();
    private static final Set<IntegrationMetricDescriptor> DESCRIPTORS = Set.copyOf(METRICS.values().stream().map(Metric::descriptor).toList());

    private BrimstoneIntegrationMetrics() {
    }

    static Set<IntegrationMetricDescriptor> descriptors() {
        return DESCRIPTORS;
    }

    static IntegrationMetricDescriptor descriptor(String key) {
        if (key == null) {
            return null;
        }
        Metric metric = METRICS.get(key);
        return metric == null ? null : metric.descriptor();
    }

    static double value(String key, Values values) {
        return METRICS.get(key).value().applyAsDouble(values);
    }

    private static Map<String, Metric> catalog() {
        Map<String, Metric> metrics = new LinkedHashMap<>();
        add(metrics, "ready", "state", "boolean", values -> 1);
        add(metrics, "active-tnt", "gauge", "entities", values -> values.runtime().activeTnt());
        add(metrics, "active-secondary", "gauge", "entities", values -> values.runtime().activeSecondary());
        add(metrics, "enabled-types", "gauge", "types", values -> values.runtime().enabledTypes());
        add(metrics, "registered-recipes", "gauge", "recipes", values -> values.runtime().registeredRecipes());
        add(metrics, "launch-attempts", "counter", "launches", values -> values.effects().launchAttempts());
        add(metrics, "launch-successes", "counter", "launches", values -> values.effects().launchSuccesses());
        add(metrics, "launch-rejections", "counter", "launches", values -> total(values.effects().launchRejections()));
        add(metrics, "quota-rejections", "counter", "requests", values -> total(values.effects().quotaRejections()));
        add(metrics, "secondary-suppressions", "counter", "entities", values -> total(values.effects().secondarySuppressions()));
        add(metrics, "particles-requested", "counter", "particles", values -> values.effects().particlesRequested());
        add(metrics, "particles-granted", "counter", "particles", values -> values.effects().particlesGranted());
        add(metrics, "particles-suppressed", "counter", "particles", values -> values.effects().particlesSuppressed());
        for (EffectDiagnostics.LaunchRejection reason : EffectDiagnostics.LaunchRejection.values()) {
            add(metrics, "launch-rejections." + key(reason), "counter", "launches", values -> values.effects().launchRejections().getOrDefault(reason, 0L));
        }
        for (EffectDiagnostics.QuotaRejection reason : EffectDiagnostics.QuotaRejection.values()) {
            add(metrics, "quota-rejections." + key(reason), "counter", "requests", values -> values.effects().quotaRejections().getOrDefault(reason, 0L));
        }
        for (EffectDiagnostics.SecondarySuppression reason : EffectDiagnostics.SecondarySuppression.values()) {
            add(metrics, "secondary-suppressions." + key(reason), "counter", "entities", values -> values.effects().secondarySuppressions().getOrDefault(reason, 0L));
        }
        return Map.copyOf(metrics);
    }

    private static void add(Map<String, Metric> metrics, String name, String kind, String unit, ToDoubleFunction<Values> value) {
        String key = "brimstone." + name;
        IntegrationMetricDescriptor descriptor = new IntegrationMetricDescriptor(key, IntegrationMetricType.LONG, unit,
                Map.of("plugin", "brimstone", "kind", kind));
        metrics.put(key, new Metric(descriptor, value));
    }

    private static String key(Enum<?> reason) {
        return reason.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static long total(Map<?, Long> values) {
        long total = 0;
        for (long value : values.values()) {
            total += value;
        }
        return total;
    }

    record Values(BrimstoneIntegrationService.RuntimeSnapshot runtime, EffectDiagnostics.Snapshot effects) {
    }

    private record Metric(IntegrationMetricDescriptor descriptor, ToDoubleFunction<Values> value) {
    }
}
