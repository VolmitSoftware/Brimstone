package com.volmit.brimstone.effect;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.brimstone.config.BrimstoneConfig;
import com.volmit.brimstone.config.RuntimeConfig;
import com.volmit.brimstone.diagnostics.EffectDiagnostics;
import com.volmit.brimstone.diagnostics.EffectDiagnostics.QuotaRejection;
import com.volmit.brimstone.diagnostics.EffectDiagnostics.SecondarySuppression;
import com.volmit.brimstone.gameplay.EntityBudget;
import com.volmit.brimstone.gameplay.ManagedEffects;
import com.volmit.brimstone.protection.LaunchContext;
import com.volmit.brimstone.protection.ProtectionService;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.Invocation;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TntEffectsTest {
    private JavaPlugin plugin;
    private RuntimeConfig configuration;
    private ManagedEffects managed;
    private World world;
    private TNTPrimed entity;
    private TntEffects effects;
    private LaunchContext context;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        configuration = RuntimeConfig.from(new BrimstoneConfig());
        managed = mock(ManagedEffects.class);
        when(managed.budget()).thenReturn(new EntityBudget(() -> 0L));
        when(managed.current(any())).thenReturn(true);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        entity = mock(TNTPrimed.class);
        when(entity.getLocation()).thenAnswer(invocation -> new Location(world, 15.5, 80, 15.5));
        when(entity.getVelocity()).thenAnswer(invocation -> new Vector(1, 0, 0));
        ProtectionService protection = mock(ProtectionService.class);
        when(protection.allows(any(), any(), any())).thenReturn(true);
        effects = new TntEffects(plugin, () -> configuration, protection, managed);
        context = new LaunchContext(UUID.randomUUID(), "Effects", List.of(), world.getUID(), false, false);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void launchesRetainTheirDistinctSoundsAndPitches() {
        assertLaunch("notch", Sound.ENTITY_TNT_PRIMED, 0.8f);
        assertLaunch("creeper", Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f);
        assertLaunch("firework", Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f);
        assertLaunch("love", Sound.ENTITY_GENERIC_EXPLODE, 0.5f);
        assertLaunch("dj", Sound.BLOCK_PISTON_EXTEND, 0.6f);
        assertLaunch("rainbow", Sound.ENTITY_HORSE_JUMP, 0.6f);
        assertLaunch("water", Sound.ENTITY_GENERIC_SWIM, 0.5f);
    }

    @Test
    void melodyRetainsOpeningRestsAndStopsAtTheEnd() {
        float[] opening = {0.84f, 0.9f, 1, 0, 1.26f, 0.9f, 0.84f, 0.9f, 1, 1.34f, 1.68f, 1.78f, 1.68f, 1.26f, 1.34f, 0};
        for (int index = 0; index < opening.length; index++) {
            assertEquals(opening[index], DjMelody.pitch(index * 2));
        }
        assertEquals(0.67f, DjMelody.pitch(952));
        assertEquals(0, DjMelody.pitch(954));
        assertEquals(0, DjMelody.pitch(Integer.MAX_VALUE));
        assertEquals(0, DjMelody.pitch(-1));
    }

    @Test
    void soundPlaybackUsesElapsedTimeEvenWhenEntityAgeIsOdd() {
        when(entity.getTicksLived()).thenReturn(13);
        effects.sound(entity, "dj", 2);
        verify(world).playSound(any(Location.class), eq(Sound.BLOCK_NOTE_BLOCK_HARP), eq(4f), eq(0.84f));
        clearInvocations(world);
        effects.sound(entity, "dj", 10);
        verify(world).playSound(any(Location.class), eq(Sound.BLOCK_NOTE_BLOCK_HARP), eq(4f), eq(1.26f));
        clearInvocations(world);
        effects.sound(entity, "dj", 954);
        verify(world).playSound(any(Location.class), eq(Sound.BLOCK_NOTE_BLOCK_HARP), eq(4f), eq(0.67f));
        clearInvocations(world);
        effects.sound(entity, "dj", 8);
        effects.sound(entity, "dj", 956);
        verifyNoInteractions(world);
        effects.sound(entity, "creeper", 5);
        verify(world).playSound(any(Location.class), eq(Sound.ENTITY_CREEPER_PRIMED), eq(3f), eq(1f));
    }

    @Test
    void particlesRespectBudgetWhileRestoringMixedTrailsAndBursts() {
        BrimstoneConfig source = new BrimstoneConfig();
        source.effects.particlesPerTick = 5;
        source.effects.burstParticles = 60;
        configuration = RuntimeConfig.from(source);
        effects.explode(entity.getLocation(), "rainbow", 8, context, managed.budget().generation());
        List<Particle.DustOptions> colors = new ArrayList<>();
        int emitted = 0;
        for (Invocation invocation : mockingDetails(world).getInvocations()) {
            if (invocation.getMethod().getName().equals("spawnParticle")) {
                emitted += invocation.getArgument(2, Integer.class);
                colors.add(invocation.getArgument(7, Particle.DustOptions.class));
            }
        }
        assertEquals(5, emitted);
        EffectDiagnostics.Snapshot diagnostics = managed.budget().diagnostics().snapshot();
        assertEquals(60, diagnostics.particlesRequested());
        assertEquals(5, diagnostics.particlesGranted());
        assertEquals(55, diagnostics.particlesSuppressed());
        assertEquals(5, colors.size());
        for (Particle.DustOptions color : colors) {
            assertTrue(color.getColor().getRed() >= 125);
            assertTrue(color.getColor().getGreen() >= 125);
            assertTrue(color.getColor().getBlue() >= 125);
        }
    }

    @Test
    void fireworksAreSpreadScheduledAndHaveShapesColorsFadesAndTwinkle() {
        List<Runnable> tasks = new ArrayList<>();
        List<Location> locations = new ArrayList<>();
        List<FireworkMeta> metas = new ArrayList<>();
        when(world.spawn(any(Location.class), eq(Firework.class), ArgumentMatchers.<Consumer<Firework>>any())).thenAnswer(invocation -> {
            Firework firework = mock(Firework.class);
            FireworkMeta meta = (FireworkMeta) new ItemStack(Material.FIREWORK_ROCKET).getItemMeta();
            when(firework.getFireworkMeta()).thenReturn(meta);
            Consumer<Firework> configure = invocation.getArgument(2);
            configure.accept(firework);
            metas.add(meta);
            return firework;
        });
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runRegion(eq(plugin), any(Location.class), any(Runnable.class))).thenAnswer(invocation -> {
                locations.add(invocation.getArgument(1));
                tasks.add(invocation.getArgument(2));
                return true;
            });
            effects.explode(entity.getLocation(), "firework", 8, context, managed.budget().generation());
            assertTrue(metas.isEmpty());
            assertEquals(5, tasks.size());
            tasks.forEach(Runnable::run);
        }
        assertEquals(5, metas.size());
        for (Location location : locations) {
            assertTrue(location.distanceSquared(entity.getLocation()) <= 144);
            assertEquals(80, location.getY());
        }
        assertTrue(locations.stream().distinct().count() > 1);
        Set<FireworkEffect.Type> shapes = Set.of(FireworkEffect.Type.BALL, FireworkEffect.Type.BALL_LARGE,
                FireworkEffect.Type.BURST, FireworkEffect.Type.STAR);
        for (FireworkMeta meta : metas) {
            assertEquals(0, meta.getPower());
            assertTrue(meta.getEffectsSize() >= 1 && meta.getEffectsSize() <= 3);
            for (FireworkEffect effect : meta.getEffects()) {
                assertTrue(shapes.contains(effect.getType()));
                assertTrue(effect.getColors().size() >= 1 && effect.getColors().size() <= 3);
                assertEquals(1, effect.getFadeColors().size());
                assertTrue(effect.hasFlicker() ^ effect.hasTrail());
            }
        }
    }

    @Test
    void waterBurstSoundDoesNotDependOnParticles() {
        BrimstoneConfig source = new BrimstoneConfig();
        source.effects.burstParticles = 0;
        configuration = RuntimeConfig.from(source);
        effects.explode(entity.getLocation(), "water", 0, context, managed.budget().generation());
        verify(world).playSound(any(Location.class), eq(Sound.ENTITY_GENERIC_SWIM), eq(1f), eq(1f));
        assertFalse(mockingDetails(world).getInvocations().stream().anyMatch(invocation -> invocation.getMethod().getName().equals("spawnParticle")));
    }

    @Test
    void delayedSecondarySpawnCancelledByClearCountsEachSuppressedChildOnce() {
        List<Runnable> tasks = new ArrayList<>();
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runRegion(eq(plugin), any(Location.class), any(Runnable.class))).thenAnswer(invocation -> {
                tasks.add(invocation.getArgument(2));
                return true;
            });
            effects.explode(entity.getLocation(), "firework", 8, context, managed.budget().generation());
            managed.budget().clear();
            when(managed.current(any())).thenAnswer(invocation -> managed.budget().current(invocation.getArgument(0)));
            tasks.forEach(Runnable::run);
        }
        EffectDiagnostics.Snapshot diagnostics = managed.budget().diagnostics().snapshot();
        assertEquals(5, diagnostics.secondarySuppressions().get(SecondarySuppression.STOPPED));
        assertEquals(5, diagnostics.secondarySuppressions().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(0, managed.budget().count(EntityBudget.Kind.SECONDARY));
    }

    @Test
    void creeperFireworkSpawnsOutsideTheExplosionHandler() {
        List<Runnable> tasks = new ArrayList<>();
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runRegion(eq(plugin), any(Location.class), any(Runnable.class))).thenAnswer(invocation -> {
                tasks.add(invocation.getArgument(2));
                return true;
            });
            scheduler.when(() -> FoliaScheduler.runRegion(eq(plugin), any(Location.class), any(Runnable.class), anyLong())).thenReturn(true);
            effects.explode(entity.getLocation(), "creeper", 8, context, managed.budget().generation());
        }
        assertEquals(1, tasks.size());
        assertFalse(mockingDetails(world).getInvocations().stream().anyMatch(invocation -> invocation.getMethod().getName().equals("spawn")));
    }

    @Test
    void secondaryQuotaAndSchedulerDenialsHaveSeparateReasonsAndReleaseReservations() {
        BrimstoneConfig source = new BrimstoneConfig();
        source.limits.secondaryPerPlayer = 1;
        configuration = RuntimeConfig.from(source);
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runRegion(eq(plugin), any(Location.class), any(Runnable.class))).thenReturn(true);
            effects.explode(entity.getLocation(), "firework", 8, context, managed.budget().generation());
            EffectDiagnostics.Snapshot quota = managed.budget().diagnostics().snapshot();
            assertEquals(4, quota.secondarySuppressions().get(SecondarySuppression.QUOTA));
            assertEquals(4, quota.quotaRejections().get(QuotaRejection.SECONDARY_PLAYER));
            managed.budget().clear();
            scheduler.when(() -> FoliaScheduler.runRegion(eq(plugin), any(Location.class), any(Runnable.class))).thenReturn(false);
            effects.explode(entity.getLocation(), "firework", 8, context, managed.budget().generation());
        }
        EffectDiagnostics.Snapshot diagnostics = managed.budget().diagnostics().snapshot();
        assertEquals(5, diagnostics.secondarySuppressions().get(SecondarySuppression.SCHEDULER));
        assertEquals(4, diagnostics.secondarySuppressions().get(SecondarySuppression.QUOTA));
        assertEquals(0, managed.budget().count(EntityBudget.Kind.SECONDARY));
    }

    @Test
    void unloadedSecondaryLocationsAreRejectedBeforeAnySpawn() {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runRegion(eq(plugin), any(Location.class), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.getArgument(2, Runnable.class).run();
                return true;
            });
            effects.explode(entity.getLocation(), "firework", 8, context, managed.budget().generation());
        }
        assertEquals(5, managed.budget().diagnostics().snapshot().secondarySuppressions().get(SecondarySuppression.UNLOADED_CHUNK));
        assertEquals(0, managed.budget().count(EntityBudget.Kind.SECONDARY));
        assertFalse(mockingDetails(world).getInvocations().stream().anyMatch(invocation -> invocation.getMethod().getName().equals("spawn")));
    }

    private void assertLaunch(String effect, Sound sound, float pitch) {
        clearInvocations(world);
        effects.launch(entity, effect);
        verify(world).playSound(any(Location.class), eq(sound), eq(1f), eq(pitch));
    }
}
