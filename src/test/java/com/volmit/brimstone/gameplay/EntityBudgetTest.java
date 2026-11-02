package com.volmit.brimstone.gameplay;

import com.volmit.brimstone.config.EntityLimits;
import com.volmit.brimstone.diagnostics.EffectDiagnostics;
import com.volmit.brimstone.diagnostics.EffectDiagnostics.QuotaRejection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityBudgetTest {
    private static final EntityLimits LIMITS = new EntityLimits(4, 8, 16, 2, 4, 8);

    @Test
    void simultaneousProducersCannotExceedAnyQuota() throws Exception {
        UUID sharedOwner = UUID.randomUUID();
        UUID sharedWorld = UUID.randomUUID();
        List<EntityLimits> limits = List.of(new EntityLimits(8, 64, 64, 2, 4, 8),
                new EntityLimits(64, 8, 64, 2, 4, 8), new EntityLimits(64, 64, 8, 2, 4, 8));
        for (int scenario = 0; scenario < limits.size(); scenario++) {
            EntityBudget budget = new EntityBudget(System::nanoTime);
            EntityLimits selected = limits.get(scenario);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<EntityBudget.Admission>> results = new ArrayList<>();
            try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
                for (int index = 0; index < 64; index++) {
                    UUID owner = scenario == 0 ? sharedOwner : UUID.randomUUID();
                    UUID world = scenario == 1 ? sharedWorld : UUID.randomUUID();
                    results.add(workers.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return budget.reserve(owner, world, EntityBudget.Kind.TNT, selected, 0);
                    }));
                }
                start.countDown();
                int admitted = 0;
                for (Future<EntityBudget.Admission> result : results) {
                    if (result.get(5, TimeUnit.SECONDS).ticket() != null) {
                        admitted++;
                    }
                }
                assertEquals(8, admitted);
                assertEquals(8, budget.count(EntityBudget.Kind.TNT));
                QuotaRejection reason = switch (scenario) {
                    case 0 -> QuotaRejection.TNT_PLAYER;
                    case 1 -> QuotaRejection.TNT_WORLD;
                    default -> QuotaRejection.TNT_SERVER;
                };
                assertEquals(56, budget.diagnostics().snapshot().quotaRejections().get(reason));
                assertEquals(56, budget.diagnostics().snapshot().quotaRejections().values().stream().mapToLong(Long::longValue).sum());
            }
        }
    }

    @Test
    void clearInvalidatesPendingSpawnsAndRejectsChildrenFromOldGeneration() {
        EntityBudget budget = new EntityBudget(System::nanoTime);
        UUID owner = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        EntityBudget.Ticket pending = budget.reserve(owner, world, EntityBudget.Kind.SECONDARY, LIMITS, 0).ticket();
        assertEquals(List.of(pending), budget.clear());
        assertFalse(budget.activate(pending));
        assertFalse(budget.current(pending));
        assertEquals(0, budget.count(EntityBudget.Kind.SECONDARY));
        assertEquals(EntityBudget.Denial.STOPPED,
                budget.reserve(owner, world, EntityBudget.Kind.SECONDARY, LIMITS, pending.generation()).denial());
        assertEquals(1, budget.diagnostics().snapshot().quotaRejections().get(QuotaRejection.SECONDARY_STOPPED));
        assertNotNull(budget.reserve(owner, world, EntityBudget.Kind.SECONDARY, LIMITS, budget.generation()).ticket());
    }

    @Test
    void activeEntitiesKeepCapacityUntilRemovalAcknowledgesClear() {
        EntityBudget budget = new EntityBudget(System::nanoTime);
        EntityLimits single = new EntityLimits(1, 1, 1, 1, 1, 1);
        UUID owner = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        EntityBudget.Ticket active = budget.reserve(owner, world, EntityBudget.Kind.TNT, single, 0).ticket();
        assertTrue(budget.activate(active));
        assertEquals(List.of(active), budget.clear());
        assertEquals(1, budget.count(EntityBudget.Kind.TNT));
        assertFalse(budget.current(active));
        assertNull(budget.reserve(owner, world, EntityBudget.Kind.TNT, single, budget.generation()).ticket());
        budget.release(active);
        budget.release(active);
        assertEquals(0, budget.count(EntityBudget.Kind.TNT));
        assertNotNull(budget.reserve(owner, world, EntityBudget.Kind.TNT, single, budget.generation()).ticket());
    }

    @Test
    void stopRejectsAdmissionAndLateActivationWithoutForgettingActiveEntities() {
        EntityBudget budget = new EntityBudget(System::nanoTime);
        UUID owner = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        EntityBudget.Ticket active = budget.reserve(owner, world, EntityBudget.Kind.TNT, LIMITS, 0).ticket();
        EntityBudget.Ticket pending = budget.reserve(owner, world, EntityBudget.Kind.SECONDARY, LIMITS, 0).ticket();
        budget.activate(active);
        assertEquals(2, budget.stop().size());
        assertFalse(budget.activate(pending));
        assertEquals(1, budget.count(EntityBudget.Kind.TNT));
        assertEquals(0, budget.count(EntityBudget.Kind.SECONDARY));
        assertEquals(EntityBudget.Denial.STOPPED,
                budget.reserve(owner, world, EntityBudget.Kind.TNT, LIMITS, budget.generation()).denial());
        assertEquals(1, budget.diagnostics().snapshot().quotaRejections().get(QuotaRejection.TNT_STOPPED));
        assertEquals(0, budget.particles(10, 100));
        assertEquals(10, budget.diagnostics().snapshot().particlesSuppressed());
        budget.release(active);
        assertEquals(0, budget.count(EntityBudget.Kind.TNT));
    }

    @Test
    void transferPreservesAllCountersWhenDestinationIsFull() {
        EntityBudget budget = new EntityBudget(System::nanoTime);
        EntityLimits limits = new EntityLimits(4, 1, 4, 1, 1, 1);
        UUID owner = UUID.randomUUID();
        UUID origin = UUID.randomUUID();
        UUID destination = UUID.randomUUID();
        EntityBudget.Ticket moving = budget.reserve(owner, origin, EntityBudget.Kind.TNT, limits, 0).ticket();
        EntityBudget.Ticket occupying = budget.reserve(owner, destination, EntityBudget.Kind.TNT, limits, 0).ticket();
        budget.activate(moving);
        assertFalse(budget.transfer(moving, destination, limits));
        assertEquals(1, budget.diagnostics().snapshot().quotaRejections().get(QuotaRejection.TNT_TRANSFER_WORLD));
        assertEquals(origin, moving.world());
        assertEquals(2, budget.count(EntityBudget.Kind.TNT));
        budget.release(occupying);
        assertTrue(budget.transfer(moving, destination, limits));
        assertEquals(destination, moving.world());
        assertNotNull(budget.reserve(owner, origin, EntityBudget.Kind.TNT, limits, 0).ticket());
        assertEquals(EntityBudget.Denial.WORLD,
                budget.reserve(owner, destination, EntityBudget.Kind.TNT, limits, 0).denial());
    }

    @Test
    void loweredLimitsStopAdmissionsWithoutEvictingExistingTickets() {
        EntityBudget budget = new EntityBudget(System::nanoTime);
        UUID owner = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        EntityBudget.Ticket first = budget.reserve(owner, world, EntityBudget.Kind.TNT, LIMITS, 0).ticket();
        EntityBudget.Ticket second = budget.reserve(owner, world, EntityBudget.Kind.TNT, LIMITS, 0).ticket();
        EntityLimits lower = new EntityLimits(1, 1, 1, 1, 1, 1);
        assertTrue(budget.current(first));
        assertTrue(budget.current(second));
        assertTrue(budget.transfer(first, world, lower));
        assertNull(budget.reserve(owner, world, EntityBudget.Kind.TNT, lower, 0).ticket());
        assertEquals(2, budget.count(EntityBudget.Kind.TNT));
        budget.release(first);
        assertNull(budget.reserve(owner, world, EntityBudget.Kind.TNT, lower, 0).ticket());
        budget.release(second);
        assertNotNull(budget.reserve(owner, world, EntityBudget.Kind.TNT, lower, 0).ticket());
    }

    @Test
    void secondaryCapacityIsIndependentAndForeignReleaseCannotCorruptCounts() {
        EntityBudget budget = new EntityBudget(System::nanoTime);
        EntityBudget other = new EntityBudget(System::nanoTime);
        EntityLimits limits = new EntityLimits(1, 1, 1, 1, 1, 1);
        UUID owner = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        EntityBudget.Ticket primary = budget.reserve(owner, world, EntityBudget.Kind.TNT, limits, 0).ticket();
        EntityBudget.Ticket secondary = budget.reserve(owner, world, EntityBudget.Kind.SECONDARY, limits, 0).ticket();
        assertNotNull(primary);
        assertNotNull(secondary);
        assertFalse(other.activate(primary));
        other.release(primary);
        assertTrue(budget.current(primary));
        assertEquals(1, budget.count(EntityBudget.Kind.TNT));
        assertEquals(0, other.count(EntityBudget.Kind.TNT));
    }

    @Test
    void particleQuotaIsSharedAndReloadCannotRefillCurrentWindow() {
        AtomicLong clock = new AtomicLong();
        EntityBudget budget = new EntityBudget(clock::get);
        assertEquals(7, budget.particles(7, 10));
        assertEquals(3, budget.particles(9, 10));
        assertEquals(0, budget.particles(1, 5));
        budget.clear();
        assertEquals(0, budget.particles(1, 10));
        clock.set(49_999_999L);
        assertEquals(0, budget.particles(1, 10));
        clock.incrementAndGet();
        assertEquals(5, budget.particles(8, 5));
        assertEquals(0, budget.particles(1, 5));
        EffectDiagnostics.Snapshot diagnostics = budget.diagnostics().snapshot();
        assertEquals(28, diagnostics.particlesRequested());
        assertEquals(15, diagnostics.particlesGranted());
        assertEquals(13, diagnostics.particlesSuppressed());
        assertEquals(0, new EntityBudget(clock::get).diagnostics().snapshot().particlesRequested());
    }
}
