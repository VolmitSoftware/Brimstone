package com.volmit.brimstone.integration;

import art.arcane.volmlib.integration.IntegrationHandshakeRequest;
import art.arcane.volmlib.integration.IntegrationHandshakeResponse;
import art.arcane.volmlib.integration.IntegrationHeartbeat;
import art.arcane.volmlib.integration.IntegrationMetricDescriptor;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationProtocolNegotiator;
import art.arcane.volmlib.integration.IntegrationProtocolVersion;
import art.arcane.volmlib.integration.IntegrationServiceContract;
import com.volmit.brimstone.diagnostics.EffectDiagnostics;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class BrimstoneIntegrationService implements IntegrationServiceContract, AutoCloseable {
    private static final IntegrationProtocolVersion CURRENT_PROTOCOL = new IntegrationProtocolVersion(1, 1);
    private static final Set<IntegrationProtocolVersion> PROTOCOLS = Set.of(new IntegrationProtocolVersion(1, 0), CURRENT_PROTOCOL);
    private static final Set<String> CAPABILITIES = Set.of("handshake", "heartbeat", "metrics", "readiness", "effect-diagnostics");

    private final String version;
    private final Supplier<RuntimeSnapshot> runtime;
    private final Supplier<EffectDiagnostics.Snapshot> diagnostics;
    private volatile IntegrationProtocolVersion negotiatedProtocol = CURRENT_PROTOCOL;
    private volatile State state = State.STARTING;
    private volatile ServicesManager registration;

    public BrimstoneIntegrationService(String version, Supplier<RuntimeSnapshot> runtime,
                                      Supplier<EffectDiagnostics.Snapshot> diagnostics) {
        this.version = Objects.requireNonNull(version, "version");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public synchronized void register(Plugin plugin) {
        if (state == State.STOPPED || registration != null) {
            return;
        }
        ServicesManager services = plugin.getServer().getServicesManager();
        services.register(IntegrationServiceContract.class, this, plugin, ServicePriority.Normal);
        registration = services;
    }

    public synchronized void markReady() {
        if (state != State.STOPPED && registration != null) {
            state = State.READY;
        }
    }

    @Override
    public synchronized void close() {
        state = State.STOPPED;
        ServicesManager services = registration;
        registration = null;
        if (services != null) {
            services.unregister(IntegrationServiceContract.class, this);
        }
    }

    public boolean registered() {
        return registration != null;
    }

    public boolean ready() {
        return state == State.READY && runtime.get().ready();
    }

    @Override
    public String pluginId() {
        return "brimstone";
    }

    @Override
    public String pluginVersion() {
        return version;
    }

    @Override
    public Set<IntegrationProtocolVersion> supportedProtocols() {
        return PROTOCOLS;
    }

    @Override
    public Set<String> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Set<IntegrationMetricDescriptor> metricDescriptors() {
        return BrimstoneIntegrationMetrics.descriptors();
    }

    @Override
    public synchronized IntegrationHandshakeResponse handshake(IntegrationHandshakeRequest request) {
        if (state == State.STOPPED) {
            return response(null, "stopped");
        }
        if (request == null) {
            return response(null, "missing-request");
        }
        Optional<IntegrationProtocolVersion> common = IntegrationProtocolNegotiator.negotiate(PROTOCOLS, request.supportedProtocols());
        if (common.isEmpty()) {
            return response(null, "no-common-protocol");
        }
        negotiatedProtocol = common.get();
        return response(negotiatedProtocol, "ok");
    }

    @Override
    public IntegrationHeartbeat heartbeat() {
        State current = state;
        boolean ready = current == State.READY && runtime.get().ready();
        return new IntegrationHeartbeat(negotiatedProtocol, ready,
                System.currentTimeMillis(), current == State.STOPPED ? "stopped" : ready ? "ready" : "starting");
    }

    @Override
    public Map<String, IntegrationMetricSample> sampleMetrics(Set<String> metricKeys) {
        if (metricKeys == null || metricKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, IntegrationMetricSample> samples = new LinkedHashMap<>();
        State current = state;
        long sampledAt = System.currentTimeMillis();
        RuntimeSnapshot snapshot = null;
        BrimstoneIntegrationMetrics.Values values = null;
        for (String key : metricKeys) {
            IntegrationMetricDescriptor descriptor = BrimstoneIntegrationMetrics.descriptor(key);
            if (descriptor == null) {
                continue;
            }
            if (snapshot == null && current == State.READY) {
                snapshot = runtime.get();
            }
            boolean ready = snapshot != null && snapshot.ready();
            if (key.equals("brimstone.ready")) {
                samples.put(key, IntegrationMetricSample.available(descriptor, ready ? 1 : 0, sampledAt));
            } else if (!ready) {
                samples.put(key, IntegrationMetricSample.unavailable(descriptor, current == State.STOPPED ? "stopped" : "starting", sampledAt));
            } else {
                if (values == null) {
                    values = new BrimstoneIntegrationMetrics.Values(snapshot, diagnostics.get());
                }
                samples.put(key, IntegrationMetricSample.available(descriptor,
                        BrimstoneIntegrationMetrics.value(key, values), sampledAt));
            }
        }
        return Map.copyOf(samples);
    }

    private IntegrationHandshakeResponse response(IntegrationProtocolVersion protocol, String message) {
        return new IntegrationHandshakeResponse(pluginId(), version, protocol != null, protocol,
                PROTOCOLS, CAPABILITIES, message, System.currentTimeMillis());
    }

    public record RuntimeSnapshot(boolean ready, int activeTnt, int activeSecondary, int enabledTypes, int registeredRecipes) {
    }

    private enum State {
        STARTING, READY, STOPPED
    }
}
