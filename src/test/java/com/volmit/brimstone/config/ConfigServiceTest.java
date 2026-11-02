package com.volmit.brimstone.config;

import com.google.gson.JsonPrimitive;
import art.arcane.volmlib.util.config.ConfigEditorDocument;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigServiceTest {
    @TempDir
    Path directory;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void metricsTogglePersistsThroughTheEditorAndRestart() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        assertTrue(service.runtime().metricsEnabled());
        ConfigEditorDocument document = service.loadEditorDocument();
        service.saveEditorDocument(document.edit(List.of("metrics", "enabled"), new JsonPrimitive(false)));
        assertFalse(service.runtime().metricsEnabled());
        ConfigService restarted = new ConfigService(directory.toFile());
        restarted.install(restarted.prepareFromDisk());
        assertFalse(restarted.runtime().metricsEnabled());
        assertFalse(restarted.editableCopy().metrics.enabled);
        assertThrows(IOException.class, () -> restarted.update(config -> config.metrics = null));
        assertFalse(restarted.runtime().metricsEnabled());
    }

    @Test
    void updateNotificationsCanBeDisabledInTheEditorAndPersist() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        assertTrue(service.runtime().updateNotifications());
        ConfigEditorDocument document = service.loadEditorDocument();
        service.saveEditorDocument(document.edit(List.of("general", "updateNotifications"), new JsonPrimitive(false)));
        assertFalse(service.runtime().updateNotifications());
        ConfigService restarted = new ConfigService(directory.toFile());
        restarted.install(restarted.prepareFromDisk());
        assertFalse(restarted.runtime().updateNotifications());
    }

    @Test
    void reservedSectionsHoldDefaultsAndRejectOutOfRangeWatcherTiming() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        String content = Files.readString(service.configFile().toPath());
        assertTrue(content.indexOf("[general]") < content.indexOf("[metrics]")
                && content.indexOf("[metrics]") < content.indexOf("[presentation]")
                && content.indexOf("[presentation]") < content.indexOf("[hotReload]")
                && content.indexOf("[hotReload]") < content.indexOf("[debug]")
                && content.indexOf("[debug]") < content.indexOf("[gameplay]"));
        assertTrue(service.runtime().hotReloadEnabled());
        assertEquals(1000L, service.runtime().hotReloadPollMillis());
        assertEquals(1500L, service.runtime().hotReloadCooldownMillis());
        assertTrue(service.runtime().hotReloadNotifyOperators());
        assertFalse(service.runtime().debugUploadEnabled());
        assertThrows(IOException.class, () -> service.update(config -> config.hotReload.pollIntervalMillis = 249L));
        assertThrows(IOException.class, () -> service.update(config -> config.hotReload.cooldownMillis = 60001L));
        assertEquals(1000L, service.runtime().hotReloadPollMillis());
    }

    @Test
    void defaultsKeepDistinctOriginalPresetsAndImmutableSnapshots() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        assertEquals(7, service.runtime().definitions().size());
        assertEquals(15, service.runtime().definitions().get("rainbow").radius());
        assertEquals(Material.REDSTONE, service.runtime().definitions().get("love").fuelMaterial());
        assertEquals(80, service.runtime().definitions().get("water").fuseTicks());
        service.editableCopy().definitions.clear();
        assertEquals(7, service.editableCopy().definitions.size());
        assertThrows(UnsupportedOperationException.class, () -> service.runtime().definitions().clear());
    }

    @Test
    void invalidUpdatesPreserveDiskRuntimeAndEditableState() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        String original = Files.readString(service.configFile().toPath());
        assertThrows(IOException.class, () -> service.update(config -> config.gameplay.cooldownTicks = -1));
        assertEquals(30, service.runtime().cooldownTicks());
        assertEquals(30, service.editableCopy().gameplay.cooldownTicks);
        assertEquals(original, Files.readString(service.configFile().toPath()));
        assertThrows(IOException.class, () -> service.update(config -> config.general.language = "../en_US"));
        service.update(config -> config.general.language = "pirate");
        assertEquals("pirate", service.runtime().language());
        service.update(config -> config.general.language = "ja_jp");
        assertEquals("ja-JP", service.runtime().language());
        Files.writeString(service.configFile().toPath(), "cooldownTicks = [");
        assertThrows(IOException.class, service::prepareFromDisk);
        assertEquals(30, service.runtime().cooldownTicks());
    }

    @Test
    void launchersCannotShareCraftingRecipes() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        IOException duplicate = assertThrows(IOException.class, () -> service.update(config -> {
            config.definitions.get("water").craftingMaterial = "COAL";
            config.definitions.get("water").craftingCount = 3;
        }));
        assertTrue(String.valueOf(duplicate.getMessage()).contains("water")
                || String.valueOf(duplicate.getCause()).contains("water"), String.valueOf(duplicate));
        assertTrue(String.valueOf(duplicate.getMessage()).contains("notch")
                || String.valueOf(duplicate.getCause()).contains("notch"), String.valueOf(duplicate));
        service.update(config -> {
            config.definitions.get("water").craftingMaterial = "COAL";
            config.definitions.get("water").craftingCount = 0;
        });
        assertEquals(0, service.runtime().definitions().get("water").craftingCount());
    }

    @Test
    void editorRejectsStaleValuesAndFreeFuelSurvivesRestart() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        ConfigEditorDocument before = service.loadEditorDocument();
        service.update(config -> config.gameplay.cooldownTicks = 60);
        assertThrows(IOException.class, () -> service.saveEditorDocument(before.edit(List.of("gameplay", "cooldownTicks"), new JsonPrimitive(90))));
        service.update(config -> config.gameplay.fuelCount = 0);
        ConfigService restarted = new ConfigService(directory.toFile());
        restarted.install(restarted.prepareFromDisk());
        assertEquals(0, restarted.runtime().definitions().get("notch").fuelCount());
        assertEquals(60, restarted.runtime().cooldownTicks());
    }

    @Test
    void accessModesMatchExactWorldNamesAndAllowEmptyAllowlist() {
        BrimstoneConfig source = new BrimstoneConfig();
        assertTrue(RuntimeConfig.from(source).access().allowedWorld("world"));
        assertEquals(AccessPolicy.WorldGuardMode.AUTO, RuntimeConfig.from(source).access().worldGuard());
        source.access.worldMode = "ALLOWLIST";
        assertFalse(RuntimeConfig.from(source).access().allowedWorld("world"));
        source.access.worlds.add("world");
        AccessPolicy allowed = RuntimeConfig.from(source).access();
        assertTrue(allowed.allowedWorld("world"));
        assertFalse(allowed.allowedWorld("World"));
        source.access.worldMode = "DENYLIST";
        AccessPolicy denied = RuntimeConfig.from(source).access();
        assertFalse(denied.allowedWorld("world"));
        assertTrue(denied.allowedWorld("World"));
    }

    @Test
    void accessSnapshotsAreImmutableAndEditorChangesSurviveRestart() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        service.update(config -> config.access.worlds.add("world"));
        AccessPolicy before = service.runtime().access();
        service.editableCopy().access.worlds.clear();
        assertEquals(1, before.worlds().size());
        assertThrows(UnsupportedOperationException.class, () -> before.worlds().clear());
        ConfigEditorDocument document = service.loadEditorDocument();
        service.saveEditorDocument(document.edit(List.of("access", "worldMode"), new JsonPrimitive("DENYLIST")));
        service.update(config -> config.access.worldGuard = "REQUIRED");
        ConfigService restarted = new ConfigService(directory.toFile());
        restarted.install(restarted.prepareFromDisk());
        assertFalse(restarted.runtime().access().allowedWorld("world"));
        assertEquals(AccessPolicy.WorldGuardMode.REQUIRED, restarted.runtime().access().worldGuard());
        assertTrue(before.allowedWorld("world"));
    }

    @Test
    void limitsAndEffectsRemainSnapshotsAndEditorChangesSurviveRestart() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        RuntimeConfig before = service.runtime();
        assertEquals(new EntityLimits(8, 64, 256, 16, 128, 512), before.limits());
        assertEquals(new EffectSettings(2, 4, 60, 4096, 600), before.effects());
        service.editableCopy().limits.tntPerPlayer = 0;
        assertEquals(8, before.limits().tntPerPlayer());
        ConfigEditorDocument document = service.loadEditorDocument();
        service.saveEditorDocument(document.edit(List.of("limits", "tntPerPlayer"), new JsonPrimitive(0)));
        service.update(config -> {
            config.limits.secondaryPerWorld = 0;
            config.effects.trailParticles = 0;
            config.effects.particlesPerTick = 0;
            config.effects.secondaryLifetimeTicks = 20;
        });
        ConfigService restarted = new ConfigService(directory.toFile());
        restarted.install(restarted.prepareFromDisk());
        assertEquals(0, restarted.runtime().limits().tntPerPlayer());
        assertEquals(0, restarted.runtime().limits().secondaryPerWorld());
        assertEquals(new EffectSettings(2, 0, 60, 0, 20), restarted.runtime().effects());
        assertEquals(new EffectSettings(2, 4, 60, 4096, 600), before.effects());
    }

    @Test
    void invalidBudgetsPreserveDiskAndRuntime() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        String original = Files.readString(service.configFile().toPath());
        RuntimeConfig installed = service.runtime();
        List<Consumer<BrimstoneConfig>> invalid = List.of(
                config -> config.limits = null,
                config -> config.effects = null,
                config -> config.limits.tntPerPlayer = -1,
                config -> config.limits.tntPerWorld = 10001,
                config -> config.limits.tntPerServer = -1,
                config -> config.limits.secondaryPerPlayer = 50001,
                config -> config.limits.secondaryPerWorld = -1,
                config -> config.limits.secondaryPerServer = 50001,
                config -> config.effects.trailIntervalTicks = 0,
                config -> config.effects.trailIntervalTicks = 201,
                config -> config.effects.trailParticles = -1,
                config -> config.effects.trailParticles = 257,
                config -> config.effects.burstParticles = -1,
                config -> config.effects.burstParticles = 4097,
                config -> config.effects.particlesPerTick = -1,
                config -> config.effects.particlesPerTick = 65537,
                config -> config.effects.secondaryLifetimeTicks = 0,
                config -> config.effects.secondaryLifetimeTicks = 72001);
        for (Consumer<BrimstoneConfig> mutation : invalid) {
            assertThrows(IOException.class, () -> service.update(mutation));
            assertSame(installed, service.runtime());
            assertEquals(original, Files.readString(service.configFile().toPath()));
        }
    }

    @Test
    void budgetBoundaryValuesAreAcceptedWithoutReorderingLimits() {
        BrimstoneConfig source = new BrimstoneConfig();
        source.limits.tntPerPlayer = 10000;
        source.limits.tntPerWorld = 0;
        source.limits.tntPerServer = 10000;
        source.limits.secondaryPerPlayer = 50000;
        source.limits.secondaryPerWorld = 0;
        source.limits.secondaryPerServer = 50000;
        source.effects.trailIntervalTicks = 200;
        source.effects.trailParticles = 256;
        source.effects.burstParticles = 4096;
        source.effects.particlesPerTick = 65536;
        source.effects.secondaryLifetimeTicks = 72000;
        RuntimeConfig runtime = RuntimeConfig.from(source);
        assertEquals(new EntityLimits(10000, 0, 10000, 50000, 0, 50000), runtime.limits());
        assertEquals(new EffectSettings(200, 256, 4096, 65536, 72000), runtime.effects());
    }

    @Test
    void invalidAccessUpdatesPreserveDiskAndRuntime() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.install(service.prepareFromDisk());
        String original = Files.readString(service.configFile().toPath());
        RuntimeConfig installed = service.runtime();
        List<Consumer<BrimstoneConfig>> invalid = List.of(
                config -> config.access = null,
                config -> config.access.worldMode = "unknown",
                config -> config.access.worldMode = null,
                config -> config.access.worldGuard = "unknown",
                config -> config.access.worldGuard = null,
                config -> config.access.worlds = null,
                config -> config.access.worlds = List.of(" "),
                config -> config.access.worlds = Arrays.asList((String) null),
                config -> config.access.worlds = List.of("world", "world"),
                config -> config.access.worlds = List.of("a".repeat(129)),
                config -> {
                    config.access.worlds = new ArrayList<>();
                    for (int index = 0; index < 129; index++) {
                        config.access.worlds.add("world" + index);
                    }
                });
        for (Consumer<BrimstoneConfig> mutation : invalid) {
            assertThrows(IOException.class, () -> service.update(mutation));
            assertSame(installed, service.runtime());
            assertEquals(original, Files.readString(service.configFile().toPath()));
        }
    }
}
