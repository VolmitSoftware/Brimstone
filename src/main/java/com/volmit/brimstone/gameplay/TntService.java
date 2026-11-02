package com.volmit.brimstone.gameplay;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.brimstone.config.RuntimeConfig;
import com.volmit.brimstone.config.TntDefinition;
import com.volmit.brimstone.diagnostics.EffectDiagnostics;
import com.volmit.brimstone.diagnostics.EffectDiagnostics.LaunchRejection;
import com.volmit.brimstone.effect.TntEffects;
import com.volmit.brimstone.protection.LaunchContext;
import com.volmit.brimstone.protection.ProtectionKind;
import com.volmit.brimstone.protection.ProtectionService;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class TntService implements Listener {
    private static final Set<String> EFFECTS = Set.of("notch", "creeper", "firework", "love", "dj", "rainbow", "water");
    private final JavaPlugin plugin;
    private final Supplier<RuntimeConfig> configuration;
    private final BiConsumer<Player, String> feedback;
    private final NamespacedKey itemKey;
    private final NamespacedKey effectKey;
    private final NamespacedKey radiusKey;
    private final NamespacedKey fireKey;
    private final TntEffects effects;
    private final ProtectionService protection;
    private final ManagedEffects managed;
    private final EffectDiagnostics diagnostics;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final List<NamespacedKey> recipes = new ArrayList<>();
    private final List<Permission> permissions = new ArrayList<>();
    private Map<String, RecipeSignature> installedRecipes = Map.of();
    private boolean recipeInstallationComplete = true;
    private volatile boolean running;
    private volatile boolean ready;
    private volatile int registeredRecipeCount;

    public TntService(JavaPlugin plugin, Supplier<RuntimeConfig> configuration, BiConsumer<Player, String> feedback) {
        this.plugin = plugin;
        this.configuration = configuration;
        this.feedback = feedback;
        itemKey = new NamespacedKey(plugin, "tnt-id");
        effectKey = new NamespacedKey(plugin, "effect");
        radiusKey = new NamespacedKey(plugin, "radius");
        fireKey = new NamespacedKey(plugin, "fire");
        protection = new ProtectionService(plugin, configuration);
        managed = new ManagedEffects(plugin, configuration);
        diagnostics = managed.budget().diagnostics();
        effects = new TntEffects(plugin, configuration, protection, managed);
    }

    public void register() {
        running = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(protection, plugin);
        managed.register();
        updateRecipes();
    }

    public void updateRecipes() {
        ready = false;
        FoliaScheduler.runGlobal(plugin, this::installRecipes);
    }

    public boolean ready() {
        return ready;
    }

    public int registeredRecipeCount() {
        return registeredRecipeCount;
    }

    public ItemStack createItem(String id, int amount) {
        TntDefinition definition = definition(id);
        if (definition == null || amount < 1 || amount > 64) {
            throw new IllegalArgumentException("Unknown TNT or amount outside 1..64");
        }
        return createItem(definition, amount);
    }

    public boolean give(Player player, String id, int amount) {
        TntDefinition definition = definition(id);
        if (definition == null || amount < 1 || amount > 2304) {
            return false;
        }
        int remaining = amount;
        while (remaining > 0) {
            int count = Math.min(remaining, 64);
            Map<Integer, ItemStack> excess = player.getInventory().addItem(createItem(definition, count));
            for (ItemStack item : excess.values()) {
                player.getWorld().dropItem(player.getLocation(), item);
            }
            remaining -= count;
        }
        return true;
    }

    public int clear() {
        return managed.clear();
    }

    public int activeCount() {
        return managed.budget().count(EntityBudget.Kind.TNT);
    }

    public int secondaryCount() {
        return managed.budget().count(EntityBudget.Kind.SECONDARY);
    }

    public EffectDiagnostics.Snapshot diagnostics() {
        return diagnostics.snapshot();
    }

    public void shutdown() {
        running = false;
        ready = false;
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(protection);
        managed.shutdown();
        HandlerList.unregisterAll(managed);
        cooldowns.clear();
        for (NamespacedKey recipe : recipes) {
            plugin.getServer().removeRecipe(recipe);
        }
        recipes.clear();
        registeredRecipeCount = 0;
        installedRecipes = Map.of();
        for (Permission permission : permissions) {
            plugin.getServer().getPluginManager().removePermission(permission);
        }
        permissions.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!running || event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.LEFT_CLICK_AIR) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isLauncher(held)) {
            return;
        }
        diagnostics.launchAttempt();
        if (event.useItemInHand() == Event.Result.DENY) {
            diagnostics.launchRejected(LaunchRejection.INTERACTION_DENIED);
            return;
        }
        RuntimeConfig snapshot = configuration.get();
        TntDefinition definition = identify(held, snapshot);
        if (definition == null) {
            diagnostics.launchRejected(LaunchRejection.UNKNOWN_TYPE);
            return;
        }
        event.setCancelled(true);
        if (!permitted(player, "launch", definition.id())) {
            diagnostics.launchRejected(LaunchRejection.PERMISSION);
            feedback.accept(player, "launch.no-permission");
            return;
        }
        if (!worldAllowed(player, snapshot)) {
            diagnostics.launchRejected(LaunchRejection.WORLD);
            feedback.accept(player, "access.world-denied");
            return;
        }
        LaunchContext context = protection.capture(player);
        if (!protection.allows(player.getLocation().add(0, 1, 0), context, ProtectionKind.TNT)) {
            diagnostics.launchRejected(LaunchRejection.PROTECTION);
            feedback.accept(player, "access.protection-denied");
            return;
        }
        long now = System.nanoTime();
        long cooldown = snapshot.cooldownTicks() * 50_000_000L;
        Long previous = cooldowns.get(player.getUniqueId());
        if (!player.hasPermission("brimstone.bypass.cooldown") && previous != null && now - previous < cooldown) {
            diagnostics.launchRejected(LaunchRejection.COOLDOWN);
            feedback.accept(player, "launch.cooldown");
            return;
        }
        ItemStack[] original = snapshot(player.getInventory().getStorageContents());
        int fuelCount = player.hasPermission("brimstone.bypass.fuel") ? 0 : definition.fuelCount();
        ItemStack[] charged = FuelTransaction.consume(original, player.getInventory().getHeldItemSlot(), definition.fuelMaterial(), fuelCount);
        if (charged == null) {
            diagnostics.launchRejected(LaunchRejection.FUEL);
            feedback.accept(player, "launch.no-fuel");
            return;
        }
        EntityBudget.Admission admission = managed.budget().reserve(player.getUniqueId(), player.getWorld().getUID(),
                EntityBudget.Kind.TNT, snapshot.limits(), managed.budget().generation());
        if (admission.ticket() == null) {
            diagnostics.launchRejected(switch (admission.denial()) {
                case PLAYER -> LaunchRejection.PLAYER_LIMIT;
                case WORLD -> LaunchRejection.WORLD_LIMIT;
                case SERVER -> LaunchRejection.SERVER_LIMIT;
                case STOPPED -> LaunchRejection.STOPPED;
            });
            feedback.accept(player, switch (admission.denial()) {
                case PLAYER -> "launch.limit-player";
                case WORLD -> "launch.limit-world";
                case SERVER, STOPPED -> "launch.limit-server";
            });
            return;
        }
        EntityBudget.Ticket ticket = admission.ticket();
        TNTPrimed entity;
        try {
            entity = player.getWorld().spawn(player.getLocation().add(0, 1, 0), TNTPrimed.class, tnt -> {
                prepare(tnt, player, definition);
                protection.tag(tnt, context, ProtectionKind.TNT);
                managed.prepare(tnt, ticket);
            });
        } catch (RuntimeException exception) {
            diagnostics.launchRejected(LaunchRejection.SPAWN_FAILED);
            managed.budget().release(ticket);
            plugin.getLogger().log(Level.SEVERE, "Could not launch TNT " + definition.id(), exception);
            return;
        }
        if (!entity.isValid()) {
            diagnostics.launchRejected(LaunchRejection.INVALID_ENTITY);
            managed.budget().release(ticket);
            return;
        }
        boolean current = managed.current(ticket);
        if (!current || !Arrays.equals(original, player.getInventory().getStorageContents())) {
            diagnostics.launchRejected(current ? LaunchRejection.INVENTORY_CHANGED : LaunchRejection.STOPPED);
            entity.remove();
            managed.budget().release(ticket);
            return;
        }
        ManagedEffects.Presentation presentation = new ManagedEffects.Presentation(
                () -> effects.trail(entity, definition.effect()),
                elapsed -> effects.sound(entity, definition.effect(), elapsed), effects.soundInterval(definition.effect()));
        if (!managed.track(entity, ticket, presentation)) {
            diagnostics.launchRejected(LaunchRejection.TRACKING_FAILED);
            return;
        }
        player.getInventory().setStorageContents(charged);
        cooldowns.put(player.getUniqueId(), now);
        diagnostics.launchSucceeded();
        effects.launch(entity, definition.effect());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null || !(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        RuntimeConfig snapshot = configuration.get();
        TntDefinition definition = identify(event.getRecipe().getResult(), snapshot);
        if (isLauncher(event.getRecipe().getResult()) && (definition == null || definition.craftingCount() <= 0 || !snapshot.craftingEnabled()
                || !permitted(player, "craft", definition.id()) || !worldAllowed(player, snapshot))) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        RuntimeConfig snapshot = configuration.get();
        TntDefinition definition = identify(event.getRecipe().getResult(), snapshot);
        if (!isLauncher(event.getRecipe().getResult())) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        if (definition == null || definition.craftingCount() <= 0 || !snapshot.craftingEnabled() || !permitted(player, "craft", definition.id())) {
            event.setCancelled(true);
            feedback.accept(player, "craft.no-permission");
        } else if (!worldAllowed(player, snapshot)) {
            event.setCancelled(true);
            feedback.accept(player, "craft.world-denied");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAutomatedCraft(CrafterCraftEvent event) {
        if (isLauncher(event.getRecipe().getResult())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isLauncher(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (isLauncher(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed entity)) {
            return;
        }
        ExplosionProfile profile = profile(entity);
        if (profile == null) {
            return;
        }
        event.setRadius(profile.radius());
        event.setFire(profile.fire());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed entity)) {
            return;
        }
        ExplosionProfile profile = profile(entity);
        if (profile != null) {
            long generation = managed.generation(entity);
            LaunchContext context = protection.context(entity);
            if (generation >= 0 && protection.allows(event.getLocation(), context, ProtectionKind.TNT)) {
                effects.explode(event.getLocation(), profile.effect(), profile.radius(), context, generation);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    private void prepare(TNTPrimed entity, Player player, TntDefinition definition) {
        entity.setSource(player);
        entity.setFuseTicks(definition.fuseTicks());
        entity.setVelocity(player.getLocation().getDirection().multiply(definition.velocity()));
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(itemKey, PersistentDataType.STRING, definition.id());
        data.set(effectKey, PersistentDataType.STRING, definition.effect());
        data.set(radiusKey, PersistentDataType.FLOAT, definition.radius());
        data.set(fireKey, PersistentDataType.BYTE, (byte) (definition.fire() ? 1 : 0));
    }

    private ExplosionProfile profile(TNTPrimed entity) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        String effect = data.get(effectKey, PersistentDataType.STRING);
        Float radius = data.get(radiusKey, PersistentDataType.FLOAT);
        Byte fire = data.get(fireKey, PersistentDataType.BYTE);
        if (effect == null || !EFFECTS.contains(effect) || radius == null || !Float.isFinite(radius) || radius < 0 || radius > 32 || fire == null) {
            return null;
        }
        return new ExplosionProfile(effect, radius, fire == 1);
    }

    private TntDefinition identify(ItemStack item, RuntimeConfig snapshot) {
        if (!isLauncher(item)) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return id == null ? null : snapshot.definitions().get(id.toLowerCase(Locale.ROOT));
    }

    private boolean isLauncher(ItemStack item) {
        return item != null && item.getType() == Material.TNT && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.STRING);
    }

    private boolean permitted(Player player, String action, String id) {
        return player.hasPermission("brimstone." + action)
                && (player.hasPermission("brimstone.bypass.access") || player.hasPermission("brimstone." + action + "." + id));
    }

    private boolean worldAllowed(Player player, RuntimeConfig snapshot) {
        return player.hasPermission("brimstone.bypass.access") || snapshot.access().allowedWorld(player.getWorld().getName());
    }

    private TntDefinition definition(String id) {
        return configuration.get().definitions().get(id.toLowerCase(Locale.ROOT));
    }

    private ItemStack createItem(TntDefinition definition, int amount) {
        ItemStack item = new ItemStack(Material.TNT, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', definition.name()));
        meta.setLore(definition.lore().stream().map(line -> ChatColor.translateAlternateColorCodes('&', line)).toList());
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, definition.id());
        item.setItemMeta(meta);
        return item;
    }

    private void installRecipes() {
        if (!running) {
            return;
        }
        RuntimeConfig snapshot = configuration.get();
        for (String id : snapshot.definitions().keySet()) {
            for (String action : List.of("craft", "launch")) {
                String node = "brimstone." + action + "." + id;
                if (plugin.getServer().getPluginManager().getPermission(node) == null) {
                    Permission permission = new Permission(node, PermissionDefault.TRUE);
                    plugin.getServer().getPluginManager().addPermission(permission);
                    permissions.add(permission);
                }
            }
        }
        Map<String, RecipeSignature> requested = recipeSignatures(snapshot);
        if (recipeInstallationComplete && requested.equals(installedRecipes)) {
            ready = true;
            return;
        }
        recipeInstallationComplete = false;
        for (NamespacedKey recipe : recipes) {
            plugin.getServer().removeRecipe(recipe);
        }
        recipes.clear();
        registeredRecipeCount = 0;
        for (TntDefinition definition : snapshot.definitions().values()) {
            if (!requested.containsKey(definition.id())) {
                continue;
            }
            NamespacedKey key = new NamespacedKey(plugin, "tnt/" + definition.id().toLowerCase(Locale.ROOT));
            ShapelessRecipe recipe = new ShapelessRecipe(key, createItem(definition, 1));
            recipe.addIngredient(new RecipeChoice.ExactChoice(new ItemStack(Material.TNT)));
            for (int index = 0; index < definition.craftingCount(); index++) {
                recipe.addIngredient(new RecipeChoice.ExactChoice(new ItemStack(definition.craftingMaterial())));
            }
            if (!plugin.getServer().addRecipe(recipe)) {
                throw new IllegalStateException("Could not register TNT recipe " + key);
            }
            recipes.add(key);
            registeredRecipeCount = recipes.size();
        }
        installedRecipes = requested;
        recipeInstallationComplete = true;
        ready = true;
    }

    private static Map<String, RecipeSignature> recipeSignatures(RuntimeConfig snapshot) {
        if (!snapshot.craftingEnabled()) {
            return Map.of();
        }
        Map<String, RecipeSignature> signatures = new LinkedHashMap<>();
        for (TntDefinition definition : snapshot.definitions().values()) {
            if (definition.craftingCount() > 0) {
                signatures.put(definition.id(), new RecipeSignature(definition.name(), definition.lore(),
                        definition.craftingMaterial(), definition.craftingCount()));
            }
        }
        return Map.copyOf(signatures);
    }

    private static ItemStack[] snapshot(ItemStack[] contents) {
        ItemStack[] snapshot = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            snapshot[index] = contents[index] == null ? null : contents[index].clone();
        }
        return snapshot;
    }

    private record ExplosionProfile(String effect, float radius, boolean fire) {
    }

    private record RecipeSignature(String name, List<String> lore, Material material, int count) {
    }
}
