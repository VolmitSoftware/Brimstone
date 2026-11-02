package com.volmit.brimstone.integration;

import art.arcane.volmlib.integration.IntegrationHandshakeRequest;
import art.arcane.volmlib.integration.IntegrationHandshakeResponse;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationProtocolVersion;
import art.arcane.volmlib.integration.IntegrationServiceContract;
import com.volmit.brimstone.diagnostics.EffectDiagnostics;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class BrimstoneIntegrationServiceTest {
    @Test
    void registrationAndShutdownAreIdempotentWithoutConsumers() {
        ServicesManager services = mock(ServicesManager.class);
        Plugin plugin = plugin(services);
        BrimstoneIntegrationService service = service(new EffectDiagnostics());
        service.markReady();
        assertFalse(service.ready());
        service.register(plugin);
        service.register(plugin);
        service.markReady();
        assertTrue(service.registered());
        assertTrue(service.ready());
        service.close();
        service.close();
        service.markReady();
        service.register(plugin);
        assertFalse(service.registered());
        assertFalse(service.ready());
        assertFalse(service.heartbeat().healthy());
        assertEquals("stopped", service.heartbeat().message());
        verify(services, times(1)).register(IntegrationServiceContract.class, service, plugin, ServicePriority.Normal);
        verify(services, times(1)).unregister(IntegrationServiceContract.class, service);
    }

    @Test
    void handshakeSelectsHighestCommonProtocolAndRejectsInvalidRequests() {
        BrimstoneIntegrationService service = service(new EffectDiagnostics());
        assertFalse(service.handshake(null).accepted());
        IntegrationHandshakeResponse response = service.handshake(request(Set.of(
                new IntegrationProtocolVersion(1, 0), new IntegrationProtocolVersion(1, 1))));
        assertTrue(response.accepted());
        assertEquals("brimstone", response.responderPluginId());
        assertEquals("2.0.0", response.responderVersion());
        assertEquals(new IntegrationProtocolVersion(1, 1), response.negotiatedProtocol());
        assertTrue(response.capabilities().containsAll(Set.of("readiness", "heartbeat", "effect-diagnostics")));
        assertFalse(service.handshake(request(Set.of(new IntegrationProtocolVersion(2, 0)))).accepted());
        service.handshake(request(Set.of(new IntegrationProtocolVersion(1, 0))));
        assertEquals(new IntegrationProtocolVersion(1, 0), service.heartbeat().protocol());
        service.close();
        assertFalse(service.handshake(request(Set.of(new IntegrationProtocolVersion(1, 0)))).accepted());
    }

    @Test
    void readinessTracksRecipeInstallationAndReloadWithoutEntityAccess() {
        AtomicReference<BrimstoneIntegrationService.RuntimeSnapshot> runtime = new AtomicReference<>(snapshot(false));
        BrimstoneIntegrationService service = new BrimstoneIntegrationService("2.0.0", runtime::get, new EffectDiagnostics()::snapshot);
        service.register(plugin(mock(ServicesManager.class)));
        service.markReady();
        assertFalse(service.heartbeat().healthy());
        assertEquals("starting", service.heartbeat().message());
        Map<String, IntegrationMetricSample> starting = service.sampleMetrics(Set.of("brimstone.ready", "brimstone.active-tnt"));
        assertEquals(0, starting.get("brimstone.ready").valueOr(-1));
        assertFalse(starting.get("brimstone.active-tnt").available());
        runtime.set(snapshot(true));
        assertTrue(service.heartbeat().healthy());
        assertEquals("ready", service.heartbeat().message());
        runtime.set(snapshot(false));
        assertFalse(service.heartbeat().healthy());
        service.close();
        assertEquals("stopped", service.heartbeat().message());
    }

    @Test
    void samplesOnlyRequestedKnownKeysWithOneCaptureAndMatchingDescriptors() {
        EffectDiagnostics effects = new EffectDiagnostics();
        effects.launchAttempt();
        effects.launchRejected(EffectDiagnostics.LaunchRejection.FUEL);
        effects.quotaRejected(EffectDiagnostics.QuotaRejection.TNT_PLAYER);
        effects.secondarySuppressed(EffectDiagnostics.SecondarySuppression.QUOTA);
        effects.particles(10, 3);
        AtomicInteger captures = new AtomicInteger();
        BrimstoneIntegrationService service = new BrimstoneIntegrationService("2.0.0", () -> snapshot(true), () -> {
            captures.incrementAndGet();
            return effects.snapshot();
        });
        service.register(plugin(mock(ServicesManager.class)));
        service.markReady();
        Set<String> requested = Set.of("brimstone.ready", "brimstone.active-tnt", "brimstone.launch-attempts",
                "brimstone.launch-rejections", "brimstone.launch-rejections.fuel", "brimstone.quota-rejections.tnt-player",
                "brimstone.secondary-suppressions.quota", "brimstone.particles-suppressed");
        Map<String, IntegrationMetricSample> samples = service.sampleMetrics(requested);
        assertEquals(requested, samples.keySet());
        assertEquals(1, captures.get());
        assertEquals(2, samples.get("brimstone.active-tnt").valueOr(-1));
        assertEquals(1, samples.get("brimstone.launch-attempts").valueOr(-1));
        assertEquals(1, samples.get("brimstone.launch-rejections").valueOr(-1));
        assertEquals(1, samples.get("brimstone.launch-rejections.fuel").valueOr(-1));
        assertEquals(1, samples.get("brimstone.quota-rejections.tnt-player").valueOr(-1));
        assertEquals(1, samples.get("brimstone.secondary-suppressions.quota").valueOr(-1));
        assertEquals(7, samples.get("brimstone.particles-suppressed").valueOr(-1));
        long sampledAt = samples.get("brimstone.ready").sampledAtMs();
        for (IntegrationMetricSample sample : samples.values()) {
            assertTrue(sample.available());
            assertTrue(service.metricDescriptors().contains(sample.descriptor()));
            assertEquals(sampledAt, sample.sampledAtMs());
        }
    }

    @Test
    void unknownKeysDoNotCaptureStateAndStoppedValuesAreUnavailable() {
        BrimstoneIntegrationService service = new BrimstoneIntegrationService("2.0.0", () -> {
            throw new AssertionError("Runtime must not be read");
        }, () -> {
            throw new AssertionError("Diagnostics must not be read");
        });
        assertTrue(service.sampleMetrics(null).isEmpty());
        assertTrue(service.sampleMetrics(Set.of()).isEmpty());
        assertTrue(service.sampleMetrics(Set.of("brimstone.unknown")).isEmpty());
        service.close();
        Map<String, IntegrationMetricSample> samples = service.sampleMetrics(Set.of("brimstone.ready", "brimstone.launch-attempts"));
        assertEquals(0, samples.get("brimstone.ready").valueOr(-1));
        assertFalse(samples.get("brimstone.launch-attempts").available());
        assertNull(samples.get("brimstone.launch-attempts").numericValue());
        assertEquals("stopped", samples.get("brimstone.launch-attempts").message());
    }

    private static BrimstoneIntegrationService service(EffectDiagnostics diagnostics) {
        return new BrimstoneIntegrationService("2.0.0", () -> snapshot(true), diagnostics::snapshot);
    }

    private static BrimstoneIntegrationService.RuntimeSnapshot snapshot(boolean ready) {
        return new BrimstoneIntegrationService.RuntimeSnapshot(ready, 2, 3, 7, 7);
    }

    private static IntegrationHandshakeRequest request(Set<IntegrationProtocolVersion> protocols) {
        return new IntegrationHandshakeRequest("consumer", "1.0.0", protocols, Set.of("metrics"), System.currentTimeMillis());
    }

    private static Plugin plugin(ServicesManager services) {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getServicesManager()).thenReturn(services);
        return plugin;
    }
}
