package com.volmit.brimstone.gameplay;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class FuelTransaction {
    private FuelTransaction() {
    }

    public static ItemStack[] consume(ItemStack[] contents, int launcherSlot, Material fuel, int required) {
        ItemStack[] result = new ItemStack[contents.length];
        for (int slot = 0; slot < contents.length; slot++) {
            result[slot] = contents[slot] == null ? null : contents[slot].clone();
        }
        ItemStack launcher = result[launcherSlot];
        if (launcher == null || launcher.getAmount() < 1) {
            return null;
        }
        launcher.setAmount(launcher.getAmount() - 1);
        if (launcher.getAmount() == 0) {
            result[launcherSlot] = null;
        }
        int remaining = required;
        ItemStack fuelTemplate = new ItemStack(fuel);
        for (int slot = 0; slot < result.length && remaining > 0; slot++) {
            ItemStack item = result[slot];
            if (item == null || !item.isSimilar(fuelTemplate)) {
                continue;
            }
            int consumed = Math.min(remaining, item.getAmount());
            remaining -= consumed;
            item.setAmount(item.getAmount() - consumed);
            if (item.getAmount() == 0) {
                result[slot] = null;
            }
        }
        return remaining == 0 ? result : null;
    }
}
