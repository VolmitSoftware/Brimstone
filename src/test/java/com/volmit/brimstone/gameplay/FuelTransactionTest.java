package com.volmit.brimstone.gameplay;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FuelTransactionTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void consumesSplitFuelAndExactlyOneLauncherWithoutMutatingOriginal() {
        ItemStack[] original = {new ItemStack(Material.TNT, 3), new ItemStack(Material.GUNPOWDER, 2), new ItemStack(Material.GUNPOWDER, 3)};
        ItemStack[] result = FuelTransaction.consume(original, 0, Material.GUNPOWDER, 4);
        assertNotNull(result);
        assertEquals(2, result[0].getAmount());
        assertNull(result[1]);
        assertEquals(1, result[2].getAmount());
        assertEquals(3, original[0].getAmount());
        assertEquals(2, original[1].getAmount());
    }

    @Test
    void insufficientFuelLeavesAllItemsUntouched() {
        ItemStack[] original = {new ItemStack(Material.TNT), new ItemStack(Material.GUNPOWDER, 2)};
        assertNull(FuelTransaction.consume(original, 0, Material.GUNPOWDER, 3));
        assertEquals(1, original[0].getAmount());
        assertEquals(2, original[1].getAmount());
    }

    @Test
    void doesNotBurnNamedItemsAsFuel() {
        ItemStack fuel = new ItemStack(Material.GUNPOWDER);
        ItemMeta meta = fuel.getItemMeta();
        meta.setDisplayName("Keepsake");
        fuel.setItemMeta(meta);
        assertNull(FuelTransaction.consume(new ItemStack[]{new ItemStack(Material.TNT), fuel}, 0, Material.GUNPOWDER, 1));
    }

    @Test
    void freeLaunchConsumesOnlyLauncher() {
        ItemStack[] result = FuelTransaction.consume(new ItemStack[]{new ItemStack(Material.TNT)}, 0, Material.GUNPOWDER, 0);
        assertNotNull(result);
        assertNull(result[0]);
    }

    @Test
    void launcherCannotCountAsItsOwnFuel() {
        assertNull(FuelTransaction.consume(new ItemStack[]{new ItemStack(Material.TNT)}, 0, Material.TNT, 1));
    }
}
