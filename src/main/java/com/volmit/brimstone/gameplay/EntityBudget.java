package com.volmit.brimstone.gameplay;

import com.volmit.brimstone.config.EntityLimits;
import com.volmit.brimstone.diagnostics.EffectDiagnostics;
import com.volmit.brimstone.diagnostics.EffectDiagnostics.QuotaRejection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class EntityBudget {
    private static final long PARTICLE_WINDOW_NANOS = 50_000_000L;

    private final LongSupplier clock;
    private final EffectDiagnostics diagnostics = new EffectDiagnostics();
    private final Set<Ticket> tickets = new HashSet<>();
    private final Counters tnt = new Counters();
    private final Counters secondary = new Counters();
    private long generation;
    private long particleWindow;
    private int emittedParticles;
    private boolean stopped;

    public EntityBudget(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        particleWindow = clock.getAsLong();
    }

    public synchronized long generation() {
        return generation;
    }

    public EffectDiagnostics diagnostics() {
        return diagnostics;
    }

    public synchronized Admission reserve(UUID owner, UUID world, Kind kind, EntityLimits limits, long expectedGeneration) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(world);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(limits);
        if (stopped || expectedGeneration != generation) {
            diagnostics.quotaRejected(kind == Kind.TNT ? QuotaRejection.TNT_STOPPED : QuotaRejection.SECONDARY_STOPPED);
            return new Admission(null, Denial.STOPPED);
        }
        Counters counters = counters(kind);
        int playerLimit = kind == Kind.TNT ? limits.tntPerPlayer() : limits.secondaryPerPlayer();
        int worldLimit = kind == Kind.TNT ? limits.tntPerWorld() : limits.secondaryPerWorld();
        int serverLimit = kind == Kind.TNT ? limits.tntPerServer() : limits.secondaryPerServer();
        if (counters.players.getOrDefault(owner, 0) >= playerLimit) {
            diagnostics.quotaRejected(kind == Kind.TNT ? QuotaRejection.TNT_PLAYER : QuotaRejection.SECONDARY_PLAYER);
            return new Admission(null, Denial.PLAYER);
        }
        if (counters.worlds.getOrDefault(world, 0) >= worldLimit) {
            diagnostics.quotaRejected(kind == Kind.TNT ? QuotaRejection.TNT_WORLD : QuotaRejection.SECONDARY_WORLD);
            return new Admission(null, Denial.WORLD);
        }
        if (counters.total >= serverLimit) {
            diagnostics.quotaRejected(kind == Kind.TNT ? QuotaRejection.TNT_SERVER : QuotaRejection.SECONDARY_SERVER);
            return new Admission(null, Denial.SERVER);
        }
        Ticket ticket = new Ticket(owner, world, kind, generation);
        tickets.add(ticket);
        counters.players.merge(owner, 1, Integer::sum);
        counters.worlds.merge(world, 1, Integer::sum);
        counters.total++;
        return new Admission(ticket, null);
    }

    public synchronized boolean activate(Ticket ticket) {
        if (!current(ticket)) {
            return false;
        }
        ticket.activated = true;
        return true;
    }

    public synchronized boolean current(Ticket ticket) {
        return ticket != null && !ticket.cancelled && !ticket.released
                && ticket.generation == generation && tickets.contains(ticket);
    }

    public synchronized void release(Ticket ticket) {
        if (ticket == null || !tickets.remove(ticket)) {
            return;
        }
        ticket.released = true;
        Counters counters = counters(ticket.kind);
        decrement(counters.players, ticket.owner);
        decrement(counters.worlds, ticket.world);
        counters.total--;
    }

    public synchronized List<Ticket> clear() {
        generation++;
        List<Ticket> invalidated = new ArrayList<>();
        for (Ticket ticket : List.copyOf(tickets)) {
            if (ticket.cancelled) {
                continue;
            }
            ticket.cancelled = true;
            invalidated.add(ticket);
            if (!ticket.activated) {
                release(ticket);
            }
        }
        return List.copyOf(invalidated);
    }

    public synchronized List<Ticket> stop() {
        stopped = true;
        return clear();
    }

    public synchronized boolean transfer(Ticket ticket, UUID world, EntityLimits limits) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(limits);
        if (!current(ticket)) {
            return false;
        }
        if (ticket.world.equals(world)) {
            return true;
        }
        Counters counters = counters(ticket.kind);
        int maximum = ticket.kind == Kind.TNT ? limits.tntPerWorld() : limits.secondaryPerWorld();
        if (counters.worlds.getOrDefault(world, 0) >= maximum) {
            diagnostics.quotaRejected(ticket.kind == Kind.TNT ? QuotaRejection.TNT_TRANSFER_WORLD : QuotaRejection.SECONDARY_TRANSFER_WORLD);
            return false;
        }
        decrement(counters.worlds, ticket.world);
        counters.worlds.merge(world, 1, Integer::sum);
        ticket.world = world;
        return true;
    }

    public synchronized int count(Kind kind) {
        return counters(Objects.requireNonNull(kind)).total;
    }

    public synchronized int particles(int requested, int maximumPerTick) {
        if (requested <= 0) {
            return 0;
        }
        if (stopped || maximumPerTick <= 0) {
            diagnostics.particles(requested, 0);
            return 0;
        }
        long now = clock.getAsLong();
        if (now - particleWindow >= PARTICLE_WINDOW_NANOS) {
            particleWindow = now;
            emittedParticles = 0;
        }
        int granted = Math.min(requested, Math.max(0, maximumPerTick - emittedParticles));
        emittedParticles += granted;
        diagnostics.particles(requested, granted);
        return granted;
    }

    private Counters counters(Kind kind) {
        return kind == Kind.TNT ? tnt : secondary;
    }

    private void decrement(Map<UUID, Integer> counters, UUID key) {
        int remaining = counters.get(key) - 1;
        if (remaining == 0) {
            counters.remove(key);
        } else {
            counters.put(key, remaining);
        }
    }

    public enum Kind {
        TNT, SECONDARY
    }

    public enum Denial {
        PLAYER, WORLD, SERVER, STOPPED
    }

    public record Admission(Ticket ticket, Denial denial) {
    }

    public static final class Ticket {
        private final UUID owner;
        private final Kind kind;
        private final long generation;
        private volatile UUID world;
        private boolean activated;
        private boolean cancelled;
        private boolean released;

        private Ticket(UUID owner, UUID world, Kind kind, long generation) {
            this.owner = owner;
            this.world = world;
            this.kind = kind;
            this.generation = generation;
        }

        public UUID owner() {
            return owner;
        }

        public UUID world() {
            return world;
        }

        public Kind kind() {
            return kind;
        }

        public long generation() {
            return generation;
        }
    }

    private static final class Counters {
        private final Map<UUID, Integer> players = new HashMap<>();
        private final Map<UUID, Integer> worlds = new HashMap<>();
        private int total;
    }
}
