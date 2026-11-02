package com.volmit.brimstone.gameplay;

import com.volmit.brimstone.config.BrimstoneConfig;
import com.volmit.brimstone.config.RuntimeConfig;
import com.volmit.brimstone.config.TntDefinition;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TntServiceTest {
    private JavaPlugin plugin;
    private TntService service;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        RuntimeConfig configuration = RuntimeConfig.from(new BrimstoneConfig());
        service = new TntService(plugin, () -> configuration, (player, key) -> { });
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void grantedItemHasStableIdentity() {
        ItemStack item = service.createItem("notch", 3);
        assertEquals(Material.TNT, item.getType());
        assertEquals(3, item.getAmount());
        assertEquals("notch", item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin, "tnt-id"), PersistentDataType.STRING));
    }

    @Test
    void clearingEntitiesDoesNotForgetDefinitions() {
        assertEquals(0, service.clear());
        assertNotNull(service.createItem("notch", 1));
    }

    @Test
    void explosionUsesPersistedSnapshotRatherThanCurrentDefinition() {
        TNTPrimed entity = mock(TNTPrimed.class);
        PersistentDataContainer data = new ItemStack(Material.TNT).getItemMeta().getPersistentDataContainer();
        data.set(new NamespacedKey(plugin, "effect"), PersistentDataType.STRING, "notch");
        data.set(new NamespacedKey(plugin, "radius"), PersistentDataType.FLOAT, 2.5f);
        data.set(new NamespacedKey(plugin, "fire"), PersistentDataType.BYTE, (byte) 0);
        when(entity.getPersistentDataContainer()).thenReturn(data);
        ExplosionPrimeEvent event = new ExplosionPrimeEvent(entity, 8, true);
        service.onPrime(event);
        assertEquals(2.5f, event.getRadius());
        assertFalse(event.getFire());
    }

    @Test
    void vanillaExplosionsRemainUnchanged() {
        TNTPrimed entity = mock(TNTPrimed.class);
        when(entity.getPersistentDataContainer()).thenReturn(new ItemStack(Material.TNT).getItemMeta().getPersistentDataContainer());
        ExplosionPrimeEvent event = new ExplosionPrimeEvent(entity, 4, false);
        service.onPrime(event);
        assertEquals(4, event.getRadius());
    }

    @Test
    void everyConfiguredDefaultEffectRetainsItsExplosionSettings() {
        RuntimeConfig configuration = RuntimeConfig.from(new BrimstoneConfig());
        for (TntDefinition definition : configuration.definitions().values()) {
            TNTPrimed entity = mock(TNTPrimed.class);
            PersistentDataContainer data = new ItemStack(Material.TNT).getItemMeta().getPersistentDataContainer();
            data.set(new NamespacedKey(plugin, "effect"), PersistentDataType.STRING, definition.effect());
            data.set(new NamespacedKey(plugin, "radius"), PersistentDataType.FLOAT, definition.radius());
            data.set(new NamespacedKey(plugin, "fire"), PersistentDataType.BYTE, (byte) (definition.fire() ? 1 : 0));
            when(entity.getPersistentDataContainer()).thenReturn(data);
            ExplosionPrimeEvent event = new ExplosionPrimeEvent(entity, 31, !definition.fire());
            service.onPrime(event);
            assertEquals(definition.radius(), event.getRadius(), definition.id());
            assertEquals(definition.fire(), event.getFire(), definition.id());
        }
    }
}
