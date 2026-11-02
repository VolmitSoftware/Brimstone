package com.volmit.brimstone.config;

import org.bukkit.Material;
import java.util.List;

public record TntDefinition(String id, String effect, String name, List<String> lore, double velocity,
                            float radius, boolean fire, int fuseTicks, Material craftingMaterial,
                            int craftingCount, Material fuelMaterial, int fuelCount) {
    public TntDefinition {
        lore = List.copyOf(lore);
    }
}
