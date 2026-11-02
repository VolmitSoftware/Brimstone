package com.volmit.brimstone.gameplay;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.brimstone.config.BrimstoneConfig;
import com.volmit.brimstone.config.RuntimeConfig;
import com.volmit.brimstone.diagnostics.EffectDiagnostics;
import com.volmit.brimstone.diagnostics.EffectDiagnostics.LaunchRejection;
import com.volmit.brimstone.diagnostics.EffectDiagnostics.QuotaRejection;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class TntDiagnosticsTest {
    private BrimstoneConfig source;
    private AtomicReference<RuntimeConfig> configuration;
    private TntService service;
    private Player player;
    private PlayerInventory inventory;
    private World world;
    private MockedStatic<FoliaScheduler> scheduler;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        source = new BrimstoneConfig();
        source.definitions.get("notch").fuelCount = 0;
        configuration = new AtomicReference<>(RuntimeConfig.from(source));
        service = new TntService(plugin, configuration::get, (sender, key) -> { });
        scheduler = mockStatic(FoliaScheduler.class);
        scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), any(), any(Runnable.class), anyLong(), any(Runnable.class)))
                .thenReturn(true);
        service.register();
        player = mock(Player.class);
        inventory = MockBukkit.getMock().addPlayer().getInventory();
        inventory.setItemInMainHand(service.createItem("notch", 4));
        when(player.getInventory()).thenReturn(inventory);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Diagnostics");
        when(player.hasPermission("brimstone.launch")).thenReturn(true);
        when(player.hasPermission("brimstone.launch.notch")).thenReturn(true);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(invocation -> new Location(world, 0, 80, 0));
        when(world.spawn(any(Location.class), eq(TNTPrimed.class), ArgumentMatchers.<Consumer<TNTPrimed>>any()))
                .thenAnswer(invocation -> {
                    TNTPrimed entity = mock(TNTPrimed.class);
                    when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
                    when(entity.isValid()).thenReturn(true);
                    when(entity.getWorld()).thenReturn(world);
                    when(entity.getLocation()).thenAnswer(call -> new Location(world, 0, 80, 0));
                    when(entity.getPersistentDataContainer()).thenReturn(new ItemStack(Material.TNT).getItemMeta().getPersistentDataContainer());
                    Consumer<TNTPrimed> prepare = invocation.getArgument(2);
                    prepare.accept(entity);
                    return entity;
                });
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        MockBukkit.unmock();
    }

    @Test
    void successfulLaunchAndCooldownAreCountedOnceWithoutChangingInventorySemantics() {
        service.onInteract(interaction());
        assertEquals(3, inventory.getItemInMainHand().getAmount());
        service.onInteract(interaction());
        EffectDiagnostics.Snapshot snapshot = service.diagnostics();
        assertEquals(2, snapshot.launchAttempts());
        assertEquals(1, snapshot.launchSuccesses());
        assertEquals(1, snapshot.launchRejections().get(LaunchRejection.COOLDOWN));
        assertEquals(1, rejected(snapshot));
        assertEquals(3, inventory.getItemInMainHand().getAmount());
        assertEquals(1, service.activeCount());
    }

    @Test
    void fuelAndPermissionFailuresAreSeparateAndReloadRetainsCounts() {
        source.definitions.get("notch").fuelCount = 1;
        configuration.set(RuntimeConfig.from(source));
        service.onInteract(interaction());
        when(player.hasPermission("brimstone.launch")).thenReturn(false);
        service.onInteract(interaction());
        EffectDiagnostics.Snapshot before = service.diagnostics();
        source.definitions.get("notch").fuelCount = 0;
        configuration.set(RuntimeConfig.from(source));
        service.updateRecipes();
        service.clear();
        assertEquals(before, service.diagnostics());
        assertEquals(2, before.launchAttempts());
        assertEquals(0, before.launchSuccesses());
        assertEquals(1, before.launchRejections().get(LaunchRejection.FUEL));
        assertEquals(1, before.launchRejections().get(LaunchRejection.PERMISSION));
        assertEquals(4, inventory.getItemInMainHand().getAmount());
        assertThrows(UnsupportedOperationException.class, () -> before.launchRejections().clear());
    }

    @Test
    void quotaFailureHasOneLaunchReasonAndOneAdmissionReason() {
        source.limits.tntPerPlayer = 1;
        configuration.set(RuntimeConfig.from(source));
        when(player.hasPermission("brimstone.bypass.cooldown")).thenReturn(true);
        service.onInteract(interaction());
        service.onInteract(interaction());
        EffectDiagnostics.Snapshot snapshot = service.diagnostics();
        assertEquals(2, snapshot.launchAttempts());
        assertEquals(1, snapshot.launchSuccesses());
        assertEquals(1, rejected(snapshot));
        assertEquals(1, snapshot.launchRejections().get(LaunchRejection.PLAYER_LIMIT));
        assertEquals(1, snapshot.quotaRejections().get(QuotaRejection.TNT_PLAYER));
        assertEquals(3, inventory.getItemInMainHand().getAmount());
    }

    @Test
    void unrelatedActionsDoNotCountButExternalDenialsAndUnknownTypesDo() {
        PlayerInteractEvent denied = interaction();
        when(denied.useItemInHand()).thenReturn(Event.Result.DENY);
        service.onInteract(denied);
        PlayerInteractEvent offHand = interaction();
        when(offHand.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        service.onInteract(offHand);
        PlayerInteractEvent rightClick = interaction();
        when(rightClick.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        service.onInteract(rightClick);
        source.definitions.remove("notch");
        configuration.set(RuntimeConfig.from(source));
        service.onInteract(interaction());
        inventory.setItemInMainHand(new ItemStack(Material.TNT));
        service.onInteract(interaction());
        EffectDiagnostics.Snapshot snapshot = service.diagnostics();
        assertEquals(2, snapshot.launchAttempts());
        assertEquals(1, snapshot.launchRejections().get(LaunchRejection.INTERACTION_DENIED));
        assertEquals(1, snapshot.launchRejections().get(LaunchRejection.UNKNOWN_TYPE));
        assertEquals(2, rejected(snapshot));
    }

    @Test
    void invalidSpawnAndConcurrentInventoryChangeReleaseQuotaWithoutReportingSuccess() {
        TNTPrimed cancelled = mock(TNTPrimed.class);
        when(world.spawn(any(Location.class), eq(TNTPrimed.class), ArgumentMatchers.<Consumer<TNTPrimed>>any()))
                .thenReturn(cancelled);
        service.onInteract(interaction());
        assertEquals(0, service.activeCount());
        assertEquals(4, inventory.getItemInMainHand().getAmount());
        TNTPrimed changed = mock(TNTPrimed.class);
        when(changed.isValid()).thenReturn(true);
        when(world.spawn(any(Location.class), eq(TNTPrimed.class), ArgumentMatchers.<Consumer<TNTPrimed>>any()))
                .thenAnswer(invocation -> {
                    inventory.setItem(1, new ItemStack(Material.STONE));
                    return changed;
                });
        service.onInteract(interaction());
        EffectDiagnostics.Snapshot snapshot = service.diagnostics();
        assertEquals(2, snapshot.launchAttempts());
        assertEquals(0, snapshot.launchSuccesses());
        assertEquals(1, snapshot.launchRejections().get(LaunchRejection.INVALID_ENTITY));
        assertEquals(1, snapshot.launchRejections().get(LaunchRejection.INVENTORY_CHANGED));
        assertEquals(2, rejected(snapshot));
        assertEquals(0, service.activeCount());
        assertEquals(4, inventory.getItemInMainHand().getAmount());
    }

    private PlayerInteractEvent interaction() {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.LEFT_CLICK_AIR);
        when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
        return event;
    }

    private long rejected(EffectDiagnostics.Snapshot snapshot) {
        return snapshot.launchRejections().values().stream().mapToLong(Long::longValue).sum();
    }
}
