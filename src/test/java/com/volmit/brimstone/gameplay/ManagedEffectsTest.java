package com.volmit.brimstone.gameplay;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.brimstone.config.BrimstoneConfig;
import com.volmit.brimstone.config.RuntimeConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManagedEffectsTest {
    private JavaPlugin plugin;
    private RuntimeConfig configuration;
    private ManagedEffects managed;
    private World world;
    private MockedStatic<FoliaScheduler> scheduler;
    private Queue<Runnable> tasks;
    private Queue<Long> delays;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        BrimstoneConfig source = new BrimstoneConfig();
        source.effects.secondaryLifetimeTicks = 1;
        configuration = RuntimeConfig.from(source);
        managed = new ManagedEffects(plugin, () -> configuration);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        tasks = new ArrayDeque<>();
        delays = new ArrayDeque<>();
        scheduler = mockStatic(FoliaScheduler.class);
        scheduler.when(() -> FoliaScheduler.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);
        scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), any(Entity.class), any(Runnable.class),
                anyLong(), any(Runnable.class))).thenAnswer(invocation -> {
                    tasks.add(invocation.getArgument(2, Runnable.class));
                    delays.add(invocation.getArgument(3, Long.class));
                    return true;
                });
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        MockBukkit.unmock();
    }

    @Test
    void soundCadenceDoesNotDependOnParticleIntervalOrInitialAge() {
        for (int interval : List.of(1, 3, 10, 200)) {
            BrimstoneConfig source = new BrimstoneConfig();
            source.effects.trailIntervalTicks = interval;
            source.effects.trailParticles = 0;
            configuration = RuntimeConfig.from(source);
            managed = new ManagedEffects(plugin, () -> configuration);
            tasks.clear();
            delays.clear();
            Entity entity = entity(true);
            when(entity.getTicksLived()).thenReturn(1);
            List<Integer> sounds = new ArrayList<>();
            List<Integer> particles = new ArrayList<>();
            EntityBudget.Ticket ticket = reserve(EntityBudget.Kind.TNT);
            managed.prepare(entity, ticket);
            assertTrue(managed.track(entity, ticket, new ManagedEffects.Presentation(
                    () -> particles.add(entity.getTicksLived() - 1), sounds::add, 2)));
            int age = 1;
            while (age < 61) {
                age += Math.toIntExact(delays.remove());
                when(entity.getTicksLived()).thenReturn(age);
                tasks.remove().run();
            }
            List<Integer> expectedSounds = new ArrayList<>();
            for (int elapsed = 2; elapsed <= 60; elapsed += 2) {
                expectedSounds.add(elapsed);
            }
            assertEquals(expectedSounds, sounds, "Particle interval " + interval);
            List<Integer> expectedParticles = new ArrayList<>();
            for (int elapsed = interval; elapsed <= 60; elapsed += interval) {
                expectedParticles.add(elapsed);
            }
            assertEquals(expectedParticles, particles);
            managed.clear();
        }
    }

    @Test
    void particleReloadDoesNotResetSoundDeadline() {
        BrimstoneConfig source = new BrimstoneConfig();
        source.effects.trailIntervalTicks = 200;
        configuration = RuntimeConfig.from(source);
        Entity entity = entity(true);
        when(entity.getTicksLived()).thenReturn(1);
        List<Integer> sounds = new ArrayList<>();
        Runnable particles = mock(Runnable.class);
        EntityBudget.Ticket ticket = reserve(EntityBudget.Kind.TNT);
        managed.prepare(entity, ticket);
        assertTrue(managed.track(entity, ticket, new ManagedEffects.Presentation(particles, sounds::add, 5)));
        when(entity.getTicksLived()).thenReturn(6);
        tasks.remove().run();
        assertEquals(List.of(5), sounds);
        verifyNoInteractions(particles);
        source.effects.trailIntervalTicks = 3;
        configuration = RuntimeConfig.from(source);
        when(entity.getTicksLived()).thenReturn(11);
        tasks.remove().run();
        assertEquals(List.of(5, 10), sounds);
        verify(particles).run();
    }

    @Test
    void delayedSoundDoesNotBurstAndClearStopsBothCallbacks() {
        Entity entity = entity(true);
        when(entity.getTicksLived()).thenReturn(1);
        List<Integer> sounds = new ArrayList<>();
        Runnable particles = mock(Runnable.class);
        EntityBudget.Ticket ticket = reserve(EntityBudget.Kind.TNT);
        managed.prepare(entity, ticket);
        assertTrue(managed.track(entity, ticket, new ManagedEffects.Presentation(particles, sounds::add, 2)));
        when(entity.getTicksLived()).thenReturn(8);
        tasks.remove().run();
        assertEquals(List.of(7), sounds);
        verify(particles).run();
        managed.clear();
        clearInvocations(particles);
        when(entity.getTicksLived()).thenReturn(20);
        tasks.remove().run();
        assertEquals(List.of(7), sounds);
        verifyNoInteractions(particles);
    }

    @Test
    void clearRemovesBothKindsAndReleasesEachReservationOnce() {
        Entity primary = entity(true);
        Entity secondary = entity(true);
        EntityBudget.Ticket first = track(primary, EntityBudget.Kind.TNT);
        EntityBudget.Ticket second = track(secondary, EntityBudget.Kind.SECONDARY);
        assertEquals(2, managed.clear());
        verify(primary).remove();
        verify(secondary).remove();
        assertFalse(managed.current(first));
        assertFalse(managed.current(second));
        assertEquals(0, managed.budget().count(EntityBudget.Kind.TNT));
        assertEquals(0, managed.budget().count(EntityBudget.Kind.SECONDARY));
        removed(primary);
        removed(secondary);
        assertEquals(0, managed.clear());
    }

    @Test
    void removalReleasesCapacityButKeepsCurrentExplosionGeneration() {
        Entity primary = entity(true);
        EntityBudget.Ticket ticket = track(primary, EntityBudget.Kind.TNT);
        removed(primary);
        removed(primary);
        assertEquals(0, managed.budget().count(EntityBudget.Kind.TNT));
        assertNull(managed.ticket(primary));
        assertFalse(managed.current(ticket));
        assertEquals(ticket.generation(), managed.generation(primary));
        managed.clear();
        assertEquals(-1, managed.generation(primary));
    }

    @Test
    void pendingSpawnCannotActivateAfterClear() {
        Entity entity = entity(true);
        EntityBudget.Ticket ticket = reserve(EntityBudget.Kind.SECONDARY);
        managed.prepare(entity, ticket);
        assertEquals(1, managed.clear());
        assertFalse(managed.track(entity, ticket, null));
        verify(entity).remove();
        assertNull(managed.ticket(entity));
        assertEquals(0, managed.budget().count(EntityBudget.Kind.SECONDARY));
    }

    @Test
    void shortLivedSecondaryExpiresAndReturnsCapacity() throws InterruptedException {
        Entity secondary = entity(true);
        track(secondary, EntityBudget.Kind.SECONDARY);
        Thread.sleep(65);
        Runnable expiration = tasks.poll();
        assertNotNull(expiration);
        expiration.run();
        verify(secondary).remove();
        assertNull(managed.ticket(secondary));
        assertEquals(0, managed.budget().count(EntityBudget.Kind.SECONDARY));
    }

    @Test
    void invalidSpawnDoesNotLeaveReservationOrScheduledWork() {
        Entity cancelled = entity(true);
        when(cancelled.isValid()).thenReturn(false);
        EntityBudget.Ticket ticket = reserve(EntityBudget.Kind.TNT);
        managed.prepare(cancelled, ticket);
        assertFalse(managed.track(cancelled, ticket, null));
        assertEquals(0, managed.budget().count(EntityBudget.Kind.TNT));
        assertTrue(tasks.isEmpty());
    }

    @Test
    void unloadingReleasesAccountingWithoutMutatingEntity() {
        Entity secondary = entity(true);
        track(secondary, EntityBudget.Kind.SECONDARY);
        EntitiesUnloadEvent unload = mock(EntitiesUnloadEvent.class);
        when(unload.getEntities()).thenReturn(List.of(secondary));
        managed.onUnload(unload);
        assertEquals(0, managed.budget().count(EntityBudget.Kind.SECONDARY));
        assertNull(managed.ticket(secondary));
        verify(secondary, never()).remove();
        removed(secondary);
        assertEquals(0, managed.budget().count(EntityBudget.Kind.SECONDARY));
    }

    @Test
    void rejectedRemovalKeepsCapacityUntilActualEntityRemoval() {
        Entity secondary = entity(true);
        EntityBudget.Ticket ticket = track(secondary, EntityBudget.Kind.SECONDARY);
        scheduler.when(() -> FoliaScheduler.isOwnedByCurrentRegion(secondary)).thenReturn(false);
        scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(secondary), any(Runnable.class),
                eq(0L), any(Runnable.class))).thenAnswer(invocation -> {
                    invocation.getArgument(4, Runnable.class).run();
                    return false;
                });
        assertEquals(1, managed.clear());
        assertEquals(1, managed.budget().count(EntityBudget.Kind.SECONDARY));
        assertEquals(ticket, managed.ticket(secondary));
        verify(secondary, never()).remove();
        removed(secondary);
        assertEquals(0, managed.budget().count(EntityBudget.Kind.SECONDARY));
        assertNull(managed.ticket(secondary));
    }

    @Test
    void previousSessionCannotPrimeOrProduceSecondaryPayloads() {
        Entity previous = entity(true);
        EntityBudget.Ticket ticket = reserve(EntityBudget.Kind.TNT);
        managed.prepare(previous, ticket);
        ManagedEffects replacement = new ManagedEffects(plugin, () -> configuration);
        assertEquals(-1, replacement.generation(previous));
        ExplosionPrimeEvent prime = new ExplosionPrimeEvent(previous, 4, false);
        replacement.onPrime(prime);
        assertTrue(prime.isCancelled());
        EntityExplodeEvent explosion = mock(EntityExplodeEvent.class);
        when(explosion.getEntity()).thenReturn(previous);
        replacement.onExplode(explosion);
        verify(explosion).setCancelled(true);
        EntitiesLoadEvent load = mock(EntitiesLoadEvent.class);
        when(load.getEntities()).thenReturn(List.of(previous));
        replacement.onLoad(load);
        verify(previous).remove();
    }

    @Test
    void vanillaEntitiesAreNotRemovedOrBlocked() {
        Entity vanilla = entity(false);
        EntitiesLoadEvent load = mock(EntitiesLoadEvent.class);
        when(load.getEntities()).thenReturn(List.of(vanilla));
        managed.onLoad(load);
        ExplosionPrimeEvent prime = new ExplosionPrimeEvent(vanilla, 4, true);
        managed.onPrime(prime);
        assertFalse(prime.isCancelled());
        removed(vanilla);
        managed.clear();
        managed.shutdown();
        verify(vanilla, never()).remove();
    }

    @Test
    void shutdownRemovesTrackedEntitiesAndPreventsFurtherAdmission() {
        scheduler.when(() -> FoliaScheduler.isFolia(plugin)).thenReturn(true);
        Entity secondary = entity(true);
        track(secondary, EntityBudget.Kind.SECONDARY);
        managed.shutdown();
        verify(secondary).remove();
        assertEquals(0, managed.budget().count(EntityBudget.Kind.SECONDARY));
        assertEquals(-1, managed.generation(secondary));
        assertNull(managed.budget().reserve(UUID.randomUUID(), world.getUID(), EntityBudget.Kind.TNT,
                configuration.limits(), managed.budget().generation()).ticket());
    }

    @Test
    void stoppingFoliaReleasesNonpersistentEntitiesWithoutTouchingHaltedRegions() {
        Entity primary = entity(true);
        Entity secondary = entity(true);
        EntityBudget.Ticket primaryTicket = track(primary, EntityBudget.Kind.TNT);
        EntityBudget.Ticket secondaryTicket = track(secondary, EntityBudget.Kind.SECONDARY);
        verify(primary, times(2)).setPersistent(false);
        verify(secondary, times(2)).setPersistent(false);
        clearInvocations(primary, secondary);
        scheduler.when(() -> FoliaScheduler.isFolia(plugin)).thenReturn(true);
        scheduler.when(() -> FoliaScheduler.isStopping(plugin.getServer())).thenReturn(true);
        managed.shutdown();
        managed.shutdown();
        verifyNoInteractions(primary, secondary);
        assertEquals(0, managed.budget().count(EntityBudget.Kind.TNT));
        assertEquals(0, managed.budget().count(EntityBudget.Kind.SECONDARY));
        assertFalse(managed.current(primaryTicket));
        assertFalse(managed.current(secondaryTicket));
        assertNull(managed.budget().reserve(UUID.randomUUID(), world.getUID(), EntityBudget.Kind.TNT,
                configuration.limits(), managed.budget().generation()).ticket());
    }

    private EntityBudget.Ticket track(Entity entity, EntityBudget.Kind kind) {
        EntityBudget.Ticket ticket = reserve(kind);
        managed.prepare(entity, ticket);
        assertTrue(managed.track(entity, ticket, null));
        return ticket;
    }

    private EntityBudget.Ticket reserve(EntityBudget.Kind kind) {
        EntityBudget.Ticket ticket = managed.budget().reserve(UUID.randomUUID(), world.getUID(), kind,
                configuration.limits(), managed.budget().generation()).ticket();
        assertNotNull(ticket);
        return ticket;
    }

    private Entity entity(boolean owned) {
        Entity entity = mock(Entity.class);
        PersistentDataContainer data = new ItemStack(Material.TNT).getItemMeta().getPersistentDataContainer();
        if (owned) {
            data.set(new NamespacedKey(plugin, "protection-kind"), PersistentDataType.STRING, "TNT");
        }
        when(entity.getPersistentDataContainer()).thenReturn(data);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getWorld()).thenReturn(world);
        when(entity.isValid()).thenReturn(true);
        return entity;
    }

    private void removed(Entity entity) {
        EntityRemoveEvent event = mock(EntityRemoveEvent.class);
        when(event.getEntity()).thenReturn(entity);
        managed.onRemoved(event);
    }
}
