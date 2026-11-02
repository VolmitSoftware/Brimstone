package com.volmit.brimstone.config;

import com.volmit.brimstone.localization.LanguageService;
import org.bukkit.Material;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record RuntimeConfig(String language, boolean craftingEnabled, int cooldownTicks, Material fuelMaterial,
                            int fuelCount, boolean debugUploadEnabled, boolean splashScreen, boolean metricsEnabled,
                            boolean updateNotifications, boolean hotReloadEnabled, long hotReloadPollMillis,
                            long hotReloadCooldownMillis, boolean hotReloadNotifyOperators,
                            AccessPolicy access, EntityLimits limits, EffectSettings effects,
                            Map<String, TntDefinition> definitions) {
    public RuntimeConfig {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(effects, "effects");
        definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
    }

    public static RuntimeConfig from(BrimstoneConfig source) {
        Objects.requireNonNull(source, "configuration");
        Objects.requireNonNull(source.general, "general");
        Objects.requireNonNull(source.metrics, "metrics");
        Objects.requireNonNull(source.presentation, "presentation");
        Objects.requireNonNull(source.hotReload, "hotReload");
        Objects.requireNonNull(source.debug, "debug");
        Objects.requireNonNull(source.gameplay, "gameplay");
        String language = LanguageService.canonicalLocale(source.general.language);
        range(source.gameplay.cooldownTicks, 0, 72000, "gameplay.cooldownTicks");
        range(source.gameplay.fuelCount, 0, 64, "gameplay.fuelCount");
        range(source.hotReload.pollIntervalMillis, 250, 60000, "hotReload.pollIntervalMillis");
        range(source.hotReload.cooldownMillis, 250, 60000, "hotReload.cooldownMillis");
        Material fuel = material(source.gameplay.fuelMaterial, "gameplay.fuelMaterial");
        if (source.definitions == null || source.definitions.isEmpty() || source.definitions.size() > 64) {
            throw new IllegalArgumentException("definitions must contain 1 to 64 launchers");
        }
        Map<String, TntDefinition> definitions = new LinkedHashMap<>();
        source.definitions.forEach((id, definition) -> {
            if (!id.matches("[a-z][a-z0-9_-]{0,31}") || definition == null) {
                throw new IllegalArgumentException("Invalid launcher definition: " + id);
            }
            String effect = Objects.requireNonNull(definition.effect, id + ".effect").toLowerCase(Locale.ROOT);
            if (!List.of("notch", "creeper", "firework", "love", "dj", "rainbow", "water").contains(effect)) {
                throw new IllegalArgumentException("Unknown effect: " + effect);
            }
            if (!Double.isFinite(definition.velocity) || definition.velocity < 0 || definition.velocity > 4) {
                throw new IllegalArgumentException(id + ".velocity must be between 0 and 4");
            }
            if (!Float.isFinite(definition.radius) || definition.radius < 0 || definition.radius > 16) {
                throw new IllegalArgumentException(id + ".radius must be between 0 and 16");
            }
            range(definition.fuseTicks, 1, 1200, id + ".fuseTicks");
            range(definition.craftingCount, 0, 8, id + ".craftingCount");
            range(definition.fuelCount, -1, 64, id + ".fuelCount");
            if (definition.name == null || definition.name.isBlank() || definition.name.length() > 256
                    || definition.lore == null || definition.lore.size() > 16
                    || definition.lore.stream().anyMatch(line -> line == null || line.length() > 512)) {
                throw new IllegalArgumentException(id + " must have a name and lore");
            }
            Material selectedFuel = definition.fuelMaterial == null || definition.fuelMaterial.isBlank()
                    ? fuel : material(definition.fuelMaterial, id + ".fuelMaterial");
            definitions.put(id, new TntDefinition(id, effect, definition.name, definition.lore, definition.velocity,
                    definition.radius, definition.fire, definition.fuseTicks,
                    material(definition.craftingMaterial, id + ".craftingMaterial"), definition.craftingCount,
                    selectedFuel, definition.fuelCount == -1 ? source.gameplay.fuelCount : definition.fuelCount));
        });
        Map<String, String> recipes = new HashMap<>();
        for (TntDefinition definition : definitions.values()) {
            if (definition.craftingCount() == 0) {
                continue;
            }
            String recipe = definition.craftingMaterial().name() + "x" + definition.craftingCount();
            String existing = recipes.putIfAbsent(recipe, definition.id());
            if (existing != null) {
                throw new IllegalArgumentException(definition.id() + " uses the same crafting recipe as " + existing
                        + "; change craftingMaterial or craftingCount");
            }
        }
        return new RuntimeConfig(language, source.gameplay.craftingEnabled, source.gameplay.cooldownTicks, fuel,
                source.gameplay.fuelCount, source.debug.uploadEnabled, source.presentation.splashScreen,
                source.metrics.enabled, source.general.updateNotifications, source.hotReload.enabled,
                source.hotReload.pollIntervalMillis, source.hotReload.cooldownMillis, source.hotReload.notifyOperators,
                AccessPolicy.from(source.access),
                EntityLimits.from(source.limits), EffectSettings.from(source.effects), definitions);
    }

    private static Material material(String name, String path) {
        Material material = name == null ? null : Material.matchMaterial(name);
        if (material == null || material.isAir() || !material.isItem()) {
            throw new IllegalArgumentException(path + " must be an item material");
        }
        return material;
    }

    private static void range(long value, long minimum, long maximum, String path) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
    }
}
