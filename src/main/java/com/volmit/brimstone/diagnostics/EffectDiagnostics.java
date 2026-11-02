package com.volmit.brimstone.diagnostics;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

public final class EffectDiagnostics {
    private final LongAdder launchAttempts = new LongAdder();
    private final LongAdder launchSuccesses = new LongAdder();
    private final Map<LaunchRejection, LongAdder> launchRejections = counters(LaunchRejection.class);
    private final Map<QuotaRejection, LongAdder> quotaRejections = counters(QuotaRejection.class);
    private final Map<SecondarySuppression, LongAdder> secondarySuppressions = counters(SecondarySuppression.class);
    private final LongAdder particlesRequested = new LongAdder();
    private final LongAdder particlesGranted = new LongAdder();
    private final LongAdder particlesSuppressed = new LongAdder();

    public void launchAttempt() {
        launchAttempts.increment();
    }

    public void launchSucceeded() {
        launchSuccesses.increment();
    }

    public void launchRejected(LaunchRejection reason) {
        launchRejections.get(reason).increment();
    }

    public void quotaRejected(QuotaRejection reason) {
        quotaRejections.get(reason).increment();
    }

    public void secondarySuppressed(SecondarySuppression reason) {
        secondarySuppressions.get(reason).increment();
    }

    public void particles(int requested, int granted) {
        particlesRequested.add(requested);
        particlesGranted.add(granted);
        particlesSuppressed.add(requested - granted);
    }

    public Snapshot snapshot() {
        return new Snapshot(launchAttempts.sum(), launchSuccesses.sum(), snapshot(launchRejections),
                snapshot(quotaRejections), snapshot(secondarySuppressions), particlesRequested.sum(),
                particlesGranted.sum(), particlesSuppressed.sum());
    }

    private static <E extends Enum<E>> Map<E, LongAdder> counters(Class<E> type) {
        Map<E, LongAdder> counters = new EnumMap<>(type);
        for (E key : type.getEnumConstants()) {
            counters.put(key, new LongAdder());
        }
        return Collections.unmodifiableMap(counters);
    }

    private static <E extends Enum<E>> Map<E, Long> snapshot(Map<E, LongAdder> counters) {
        Map<E, Long> values = new EnumMap<>(counters.keySet().iterator().next().getDeclaringClass());
        counters.forEach((key, value) -> values.put(key, value.sum()));
        return Collections.unmodifiableMap(values);
    }

    public enum LaunchRejection {
        INTERACTION_DENIED, UNKNOWN_TYPE, PERMISSION, WORLD, PROTECTION, COOLDOWN, FUEL,
        PLAYER_LIMIT, WORLD_LIMIT, SERVER_LIMIT, STOPPED, SPAWN_FAILED, INVALID_ENTITY,
        INVENTORY_CHANGED, TRACKING_FAILED
    }

    public enum QuotaRejection {
        TNT_PLAYER, TNT_WORLD, TNT_SERVER, TNT_STOPPED,
        SECONDARY_PLAYER, SECONDARY_WORLD, SECONDARY_SERVER, SECONDARY_STOPPED,
        TNT_TRANSFER_WORLD, SECONDARY_TRANSFER_WORLD
    }

    public enum SecondarySuppression {
        QUOTA, STOPPED, SCHEDULER, UNLOADED_CHUNK, PROTECTION, SPAWN_FAILED, TRACKING_FAILED
    }

    public record Snapshot(long launchAttempts, long launchSuccesses, Map<LaunchRejection, Long> launchRejections,
                           Map<QuotaRejection, Long> quotaRejections, Map<SecondarySuppression, Long> secondarySuppressions,
                           long particlesRequested, long particlesGranted, long particlesSuppressed) {
        public Snapshot {
            launchRejections = Map.copyOf(launchRejections);
            quotaRejections = Map.copyOf(quotaRejections);
            secondarySuppressions = Map.copyOf(secondarySuppressions);
        }
    }
}
