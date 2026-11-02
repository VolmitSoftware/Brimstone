package com.volmit.brimstone.gameplay;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.brimstone.config.RuntimeConfig;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.FireworkExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.IntConsumer;
import java.util.logging.Level;

public final class ManagedEffects implements Listener {
    private final JavaPlugin plugin;
    private final Supplier<RuntimeConfig> configuration;
    private final EntityBudget budget = new EntityBudget(System::nanoTime);
    private final Map<UUID, Tracked> entities = new ConcurrentHashMap<>();
    private final NamespacedKey ownershipKey;
    private final NamespacedKey generationKey;
    private final NamespacedKey sessionKey;
    private final String session = UUID.randomUUID().toString();
    private volatile boolean stopped;

    public ManagedEffects(JavaPlugin plugin, Supplier<RuntimeConfig> configuration) {
        this.plugin = plugin;
        this.configuration = configuration;
        ownershipKey = new NamespacedKey(plugin, "protection-kind");
        generationKey = new NamespacedKey(plugin, "effect-generation");
        sessionKey = new NamespacedKey(plugin, "effect-session");
    }

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        FoliaScheduler.runGlobal(plugin, this::cleanLoadedEntities);
    }

    public EntityBudget budget() {
        return budget;
    }

    public void prepare(Entity entity, EntityBudget.Ticket ticket) {
        entity.setPersistent(false);
        entity.getPersistentDataContainer().set(generationKey, PersistentDataType.LONG, ticket.generation());
        entity.getPersistentDataContainer().set(sessionKey, PersistentDataType.STRING, session);
    }

    public long generation(Entity entity) {
        Long generation = entity.getPersistentDataContainer().get(generationKey, PersistentDataType.LONG);
        return !stopped && generation != null && generation == budget.generation()
                && session.equals(entity.getPersistentDataContainer().get(sessionKey, PersistentDataType.STRING)) ? generation : -1;
    }

    public boolean track(Entity entity, EntityBudget.Ticket ticket, Presentation presentation) {
        long lifetime = ticket.kind() == EntityBudget.Kind.SECONDARY
                ? configuration.get().effects().secondaryLifetimeTicks() * 50_000_000L : Long.MAX_VALUE;
        Tracked tracked = new Tracked(entity, ticket,
                lifetime == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + lifetime, presentation);
        entity.setPersistent(false);
        if (entities.putIfAbsent(tracked.id, tracked) != null) {
            budget.release(ticket);
            return false;
        }
        if (!entity.isValid() || !budget.activate(ticket)) {
            remove(tracked);
            return false;
        }
        return schedule(tracked);
    }

    public EntityBudget.Ticket ticket(Entity entity) {
        Tracked tracked = entities.get(entity.getUniqueId());
        return tracked == null ? null : tracked.ticket;
    }

    public boolean current(EntityBudget.Ticket ticket) {
        return ticket != null && budget.current(ticket);
    }

    public int clear() {
        List<EntityBudget.Ticket> invalidated = budget.clear();
        for (Tracked tracked : entities.values()) {
            if (!budget.current(tracked.ticket)) {
                requestRemoval(tracked, null);
            }
        }
        return invalidated.size();
    }

    public void shutdown() {
        if (stopped) {
            return;
        }
        stopped = true;
        budget.stop();
        List<Tracked> snapshot = List.copyOf(entities.values());
        if (FoliaScheduler.isFolia(plugin) && FoliaScheduler.isStopping(plugin.getServer())) {
            for (Tracked tracked : snapshot) {
                release(tracked.id);
            }
            return;
        }
        CountDownLatch drained = new CountDownLatch(snapshot.size());
        for (Tracked tracked : snapshot) {
            requestRemoval(tracked, drained);
        }
        try {
            if (!drained.await(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out removing " + drained.getCount()
                        + " owned effects during shutdown; owned entities are not saved to disk.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.WARNING, "Interrupted while removing owned effects during shutdown", exception);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) {
            shutdown();
        }
    }

    @EventHandler
    public void onRemoved(EntityRemoveEvent event) {
        release(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        if (owned(event.getEntity()) && generation(event.getEntity()) < 0) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (owned(event.getEntity()) && generation(event.getEntity()) < 0) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFirework(FireworkExplodeEvent event) {
        if (owned(event.getEntity()) && generation(event.getEntity()) < 0) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (owned(event.getDamager()) && generation(event.getDamager()) < 0) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            release(entity.getUniqueId());
        }
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            removeUntracked(entity);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent event) {
        Tracked tracked = entities.get(event.getEntity().getUniqueId());
        Location target = event.getTo();
        if (tracked != null && target != null && target.getWorld() != null
                && !budget.transfer(tracked.ticket, target.getWorld().getUID(), configuration.get().limits())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void afterTeleport(EntityTeleportEvent event) {
        if (!event.isCancelled()) {
            return;
        }
        Tracked tracked = entities.get(event.getEntity().getUniqueId());
        if (tracked != null && !budget.transfer(tracked.ticket, event.getFrom().getWorld().getUID(), configuration.get().limits())) {
            requestRemoval(tracked, null);
        }
    }

    private boolean schedule(Tracked tracked) {
        int interval = 10;
        if (tracked.presentation != null) {
            int age = tracked.entity.getTicksLived();
            interval = Math.min(interval, Math.max(1,
                    configuration.get().effects().trailIntervalTicks() - (age - tracked.lastPulse)));
            if (tracked.presentation.soundInterval() > 0) {
                interval = Math.min(interval, Math.max(1, tracked.nextSound - age));
            }
        }
        if (tracked.expiresAt != Long.MAX_VALUE) {
            long remaining = Math.max(1, (tracked.expiresAt - System.nanoTime() + 49_999_999L) / 50_000_000L);
            interval = (int) Math.min(interval, remaining);
        }
        boolean scheduled = FoliaScheduler.runEntity(plugin, tracked.entity, () -> tick(tracked),
                Math.min(interval, 10), () -> release(tracked.id));
        if (!scheduled) {
            remove(tracked);
        }
        return scheduled;
    }

    private void tick(Tracked tracked) {
        if (entities.get(tracked.id) != tracked) {
            return;
        }
        Entity entity = tracked.entity;
        if (!entity.isValid() || !budget.current(tracked.ticket) || System.nanoTime() >= tracked.expiresAt
                || !budget.transfer(tracked.ticket, entity.getWorld().getUID(), configuration.get().limits())) {
            remove(tracked);
            return;
        }
        try {
            if (tracked.presentation != null) {
                int age = entity.getTicksLived();
                if (age - tracked.lastPulse >= configuration.get().effects().trailIntervalTicks()) {
                    tracked.presentation.particles().run();
                    tracked.lastPulse = age;
                }
                if (tracked.presentation.soundInterval() > 0 && age >= tracked.nextSound) {
                    tracked.presentation.sound().accept(age - tracked.startedAt);
                    tracked.nextSound = age + tracked.presentation.soundInterval();
                }
            }
            schedule(tracked);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Owned effect failed for " + tracked.id, exception);
            remove(tracked);
        }
    }

    private void requestRemoval(Tracked tracked, CountDownLatch drained) {
        AtomicBoolean completed = new AtomicBoolean();
        Runnable retire = () -> {
            if (completed.compareAndSet(false, true)) {
                release(tracked.id);
                if (drained != null) {
                    drained.countDown();
                }
            }
        };
        Runnable removal = () -> {
            try {
                remove(tracked);
                retire.run();
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not remove owned effect " + tracked.id, exception);
                if (completed.compareAndSet(false, true) && drained != null) {
                    drained.countDown();
                }
            }
        };
        if (FoliaScheduler.isOwnedByCurrentRegion(tracked.entity)) {
            removal.run();
        } else {
            AtomicInteger outcome = new AtomicInteger();
            AtomicBoolean retired = new AtomicBoolean();
            Runnable retirement = () -> {
                retired.set(true);
                if (outcome.get() == 1) {
                    retire.run();
                }
            };
            boolean scheduled = FoliaScheduler.runEntity(plugin, tracked.entity, removal, 0, retirement);
            outcome.set(scheduled ? 1 : 2);
            if (scheduled && retired.get()) {
                retire.run();
            } else if (!scheduled) {
                plugin.getLogger().warning("Could not schedule removal of owned effect " + tracked.id
                        + "; its capacity remains reserved until removal is observed.");
                if (drained != null) {
                    drained.countDown();
                }
            }
        }
    }

    private void remove(Tracked tracked) {
        if (tracked.entity.isValid()) {
            tracked.entity.remove();
        }
        release(tracked.id);
    }

    private void release(UUID id) {
        Tracked tracked = entities.remove(id);
        if (tracked != null) {
            budget.release(tracked.ticket);
        }
    }

    private boolean owned(Entity entity) {
        return entity.getPersistentDataContainer().has(ownershipKey, PersistentDataType.STRING);
    }

    private void removeUntracked(Entity entity) {
        if (owned(entity) && !entities.containsKey(entity.getUniqueId())) {
            entity.remove();
        }
    }

    private void cleanLoadedEntities() {
        Queue<Chunk> chunks = new ArrayDeque<>();
        for (World world : plugin.getServer().getWorlds()) {
            chunks.addAll(List.of(world.getLoadedChunks()));
        }
        cleanChunks(chunks);
    }

    private void cleanChunks(Queue<Chunk> chunks) {
        if (stopped) {
            return;
        }
        for (int index = 0; index < 32 && !chunks.isEmpty(); index++) {
            Chunk chunk = chunks.remove();
            Location location = new Location(chunk.getWorld(), chunk.getX() << 4, 0, chunk.getZ() << 4);
            FoliaScheduler.runRegion(plugin, location, () -> {
                if (chunk.isLoaded()) {
                    for (Entity entity : chunk.getEntities()) {
                        removeUntracked(entity);
                    }
                }
            });
        }
        if (!chunks.isEmpty()) {
            FoliaScheduler.runGlobal(plugin, () -> cleanChunks(chunks), 1);
        }
    }

    public record Presentation(Runnable particles, IntConsumer sound, int soundInterval) {
        public Presentation {
            Objects.requireNonNull(particles, "particles");
            Objects.requireNonNull(sound, "sound");
            if (soundInterval < 0) {
                throw new IllegalArgumentException("Sound interval must not be negative");
            }
        }
    }

    private static final class Tracked {
        private final UUID id;
        private final Entity entity;
        private final EntityBudget.Ticket ticket;
        private final long expiresAt;
        private final Presentation presentation;
        private final int startedAt;
        private int lastPulse;
        private int nextSound;

        private Tracked(Entity entity, EntityBudget.Ticket ticket, long expiresAt, Presentation presentation) {
            this.id = entity.getUniqueId();
            this.entity = entity;
            this.ticket = ticket;
            this.expiresAt = expiresAt;
            this.presentation = presentation;
            this.startedAt = entity.getTicksLived();
            this.lastPulse = startedAt;
            this.nextSound = startedAt + (presentation == null ? 0 : presentation.soundInterval());
        }
    }
}
