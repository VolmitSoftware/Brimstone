package com.volmit.brimstone.protection;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.brimstone.config.AccessPolicy;
import com.volmit.brimstone.config.RuntimeConfig;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.FireworkExplodeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class ProtectionService implements Listener {
    private final JavaPlugin plugin;
    private final Supplier<RuntimeConfig> configuration;
    private final NamespacedKey ownerKey;
    private final NamespacedKey nameKey;
    private final NamespacedKey groupsKey;
    private final NamespacedKey worldKey;
    private final NamespacedKey bypassKey;
    private final NamespacedKey regionBypassKey;
    private final NamespacedKey kindKey;
    private final Set<String> loggedFailures = ConcurrentHashMap.newKeySet();
    private final Plugin worldGuardPlugin;
    private final WorldGuardProtection worldGuard;

    public ProtectionService(JavaPlugin plugin, Supplier<RuntimeConfig> configuration) {
        this.plugin = plugin;
        this.configuration = configuration;
        ownerKey = new NamespacedKey(plugin, "owner");
        nameKey = new NamespacedKey(plugin, "owner-name");
        groupsKey = new NamespacedKey(plugin, "owner-groups");
        worldKey = new NamespacedKey(plugin, "launch-world");
        bypassKey = new NamespacedKey(plugin, "world-bypass");
        regionBypassKey = new NamespacedKey(plugin, "region-bypass");
        kindKey = new NamespacedKey(plugin, "protection-kind");
        worldGuardPlugin = plugin.getServer().getPluginManager().getPlugin("WorldGuard");
        WorldGuardProtection integration = null;
        if (worldGuardPlugin != null && worldGuardPlugin.isEnabled()) {
            try {
                integration = new WorldGuardProtection();
                plugin.getLogger().info("WorldGuard protection enabled.");
            } catch (RuntimeException | LinkageError exception) {
                failure("WorldGuard integration could not initialize; protected actions will be denied", exception);
            }
        }
        worldGuard = integration;
    }

    public LaunchContext capture(Player player) {
        boolean bypass = player.hasPermission("brimstone.bypass.access");
        if (configuration.get().access().worldGuard() != AccessPolicy.WorldGuardMode.DISABLED && worldGuard != null) {
            try {
                return worldGuard.capture(player, bypass);
            } catch (RuntimeException | LinkageError exception) {
                failure("WorldGuard could not capture launcher permissions", exception);
                return null;
            }
        }
        return new LaunchContext(player.getUniqueId(), player.getName(), List.of(), player.getWorld().getUID(), bypass, false);
    }

    public boolean allows(Location location, LaunchContext context, ProtectionKind kind) {
        if (context == null || kind == null || location == null || location.getWorld() == null) {
            return false;
        }
        AccessPolicy policy = configuration.get().access();
        if (!context.worldBypass() && !policy.allowedWorld(location.getWorld().getName())) {
            return false;
        }
        if (policy.worldGuard() == AccessPolicy.WorldGuardMode.DISABLED) {
            return true;
        }
        if (worldGuardPlugin == null) {
            return policy.worldGuard() == AccessPolicy.WorldGuardMode.AUTO;
        }
        if (worldGuard == null || !worldGuardPlugin.isEnabled()) {
            return false;
        }
        try {
            return worldGuard.allows(location, context, kind);
        } catch (RuntimeException | LinkageError exception) {
            failure("WorldGuard protection query failed; denying TNT effects", exception);
            return false;
        }
    }

    public void tag(Entity entity, LaunchContext context, ProtectionKind kind) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(ownerKey, PersistentDataType.STRING, context.owner().toString());
        data.set(nameKey, PersistentDataType.STRING, context.name());
        data.set(groupsKey, PersistentDataType.LIST.strings(), context.groups());
        data.set(worldKey, PersistentDataType.STRING, context.worldId().toString());
        data.set(bypassKey, PersistentDataType.BYTE, (byte) (context.worldBypass() ? 1 : 0));
        data.set(regionBypassKey, PersistentDataType.BYTE, (byte) (context.regionBypass() ? 1 : 0));
        data.set(kindKey, PersistentDataType.STRING, kind.name());
    }

    public LaunchContext context(Entity entity) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        String owner = data.get(ownerKey, PersistentDataType.STRING);
        String name = data.get(nameKey, PersistentDataType.STRING);
        String world = data.get(worldKey, PersistentDataType.STRING);
        List<String> groups = data.get(groupsKey, PersistentDataType.LIST.strings());
        if (owner == null || name == null || world == null || groups == null) {
            return null;
        }
        try {
            return new LaunchContext(UUID.fromString(owner), name, groups, UUID.fromString(world),
                    data.getOrDefault(bypassKey, PersistentDataType.BYTE, (byte) 0) == 1,
                    data.getOrDefault(regionBypassKey, PersistentDataType.BYTE, (byte) 0) == 1);
        } catch (IllegalArgumentException exception) {
            failure("Invalid TNT ownership data", exception);
            return null;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        Entity entity = event.getEntity();
        if (owned(entity) && !allows(entity.getLocation(), context(entity), kind(entity))) {
            event.setCancelled(true);
            FoliaScheduler.runEntity(plugin, entity, entity::remove);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        if (!owned(entity)) {
            return;
        }
        LaunchContext context = context(entity);
        ProtectionKind kind = kind(entity);
        if (!allows(event.getLocation(), context, kind)) {
            event.setCancelled(true);
            return;
        }
        List<Block> blocks = event.blockList();
        if (blocks.isEmpty()) {
            return;
        }
        if (uniform(event.getLocation().getWorld(), blocks, context)) {
            if (!allows(blocks.getFirst().getLocation(), context, kind)) {
                blocks.clear();
            }
            return;
        }
        blocks.removeIf(block -> !allows(block.getLocation(), context, kind));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFirework(FireworkExplodeEvent event) {
        Entity entity = event.getEntity();
        if (owned(entity) && !allows(entity.getLocation(), context(entity), ProtectionKind.FIREWORK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity source = event.getDamager();
        if (!owned(source)) {
            return;
        }
        LaunchContext context = context(source);
        Entity victim = event.getEntity();
        Location target = victim.getLocation();
        ProtectionKind damage = context == null ? null : damage(victim, context);
        if (!allows(source.getLocation(), context, kind(source)) || !allows(target, context, kind(source))
                || damage != null && !allows(target, context, damage)) {
            event.setCancelled(true);
        }
    }

    private static ProtectionKind damage(Entity victim, LaunchContext context) {
        if (victim instanceof Player) {
            return context.owner().equals(victim.getUniqueId()) ? null : ProtectionKind.PLAYER_DAMAGE;
        }
        if (victim instanceof Vehicle && !(victim instanceof LivingEntity)) {
            return ProtectionKind.VEHICLE_DAMAGE;
        }
        if (victim instanceof LivingEntity && !(victim instanceof Enemy) && !(victim instanceof Ambient)) {
            return ProtectionKind.ANIMAL_DAMAGE;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Entity source = event.getIgnitingEntity();
        if (source != null && owned(source)
                && (!allows(event.getBlock().getLocation(), context(source), kind(source))
                || !allows(event.getBlock().getLocation(), context(source), ProtectionKind.FIRE))) {
            event.setCancelled(true);
        }
    }

    boolean uniform(World world, List<Block> blocks, LaunchContext context) {
        if (configuration.get().access().worldGuard() == AccessPolicy.WorldGuardMode.DISABLED || worldGuardPlugin == null
                || worldGuard == null || !worldGuardPlugin.isEnabled()) {
            return true;
        }
        try {
            return worldGuard.uniform(world, bounds(blocks), context);
        } catch (RuntimeException | LinkageError exception) {
            failure("WorldGuard region lookup failed; checking exploded blocks individually", exception);
            return false;
        }
    }

    private boolean owned(Entity entity) {
        return entity.getPersistentDataContainer().has(kindKey, PersistentDataType.STRING);
    }

    private ProtectionKind kind(Entity entity) {
        String kind = entity.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        try {
            return kind == null ? null : ProtectionKind.valueOf(kind);
        } catch (IllegalArgumentException exception) {
            failure("Invalid TNT effect kind", exception);
            return null;
        }
    }

    private static BoundingBox bounds(List<Block> blocks) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Block block : blocks) {
            minX = Math.min(minX, block.getX());
            minY = Math.min(minY, block.getY());
            minZ = Math.min(minZ, block.getZ());
            maxX = Math.max(maxX, block.getX());
            maxY = Math.max(maxY, block.getY());
            maxZ = Math.max(maxZ, block.getZ());
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private void failure(String message, Throwable exception) {
        if (loggedFailures.add(message)) {
            plugin.getLogger().log(Level.SEVERE, message, exception);
        }
    }
}
