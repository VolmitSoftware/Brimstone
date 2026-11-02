package com.volmit.brimstone.config;

import art.arcane.volmlib.util.config.ConfigJson;
import art.arcane.volmlib.util.config.ConfigDescription;
import art.arcane.volmlib.util.config.ConfigDoc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigDescription("Brimstone TNT launchers, effects, limits, and protection settings.")
public final class BrimstoneConfig {
    public General general = new General();
    public Metrics metrics = new Metrics();
    public Presentation presentation = new Presentation();
    public HotReload hotReload = new HotReload();
    public Debug debug = new Debug();
    public Gameplay gameplay = new Gameplay();
    public Access access = new Access();
    public Limits limits = new Limits();
    public Effects effects = new Effects();
    public Map<String, Definition> definitions = defaults();

    public BrimstoneConfig copy() {
        return ConfigJson.fromJson(ConfigJson.toJson(this, false), BrimstoneConfig.class);
    }

    private static Map<String, Definition> defaults() {
        Map<String, Definition> result = new LinkedHashMap<>();
        result.put("notch", definition("notch", "&bNotch's Bomb", 0.5, 8, true, "COAL", 3,
                "&7Notch's long lost experimental, coal-powered bomb"));
        result.put("creeper", definition("creeper", "&aCreeper Bomb", 2.5, 8, true, "GUNPOWDER", 8, "&7Hissss....."));
        result.put("firework", definition("firework", "&bFirework Supreme", 0.69, 10, false, "FIREWORK_STAR", 4,
                "&7A firework trail with an explosive finish."));
        Definition love = definition("love", "&cCupid's Love Bomb", 1.25, 3, true, "REDSTONE", 2,
                "&cSend your enemies some love.");
        love.fuelMaterial = "REDSTONE";
        result.put("love", love);
        result.put("dj", definition("dj", "&dDJ's Bass Drop", 1.69, 10, true, "DIAMOND", 1,
                "&7Drop the bass."));
        result.put("rainbow", definition("rainbow", "&cLuc&eky R&aain&bbow", 1.0, 15, false, "GOLD_INGOT", 3,
                "&7What lies at the end of the rainbow..?"));
        result.put("water", definition("water", "&bWater", 1.5, 5, false, "COD", 1,
                "&7A splash before the blast."));
        return result;
    }

    private static Definition definition(String effect, String name, double velocity, float radius, boolean fire,
                                         String material, int count, String description) {
        Definition definition = new Definition();
        definition.effect = effect;
        definition.name = name;
        definition.velocity = velocity;
        definition.radius = radius;
        definition.fire = fire;
        definition.craftingMaterial = material;
        definition.craftingCount = count;
        definition.lore = new ArrayList<>(List.of(description, "&7Left-click the air to launch."));
        return definition;
    }

    public static final class General {
        @ConfigDoc(value = "Server default locale stored as the editable languages/<locale>.toml file.", impact = "Repository locales download when first selected and merge repository changes while keeping local edits; custom files may name any locale, and missing entries use built-in English.")
        public String language = "en_US";
        @ConfigDoc(value = "Check GitHub releases and notify players with brimstone.update about Brimstone updates.", impact = "The check runs once at startup. The plugin never downloads or installs updates.")
        public boolean updateNotifications = true;
    }

    public static final class Metrics {
        @ConfigDoc(value = "Submit anonymous Brimstone usage statistics to bStats.", impact = "The global plugins/bStats/config.yml opt-out remains authoritative; changes apply immediately without a restart.")
        public boolean enabled = true;
    }

    public static final class Presentation {
        @ConfigDoc(value = "Print the Brimstone startup splash after services are ready.", impact = "Read once at startup; changes apply on the next start.")
        public boolean splashScreen = true;
    }

    public static final class HotReload {
        @ConfigDoc(value = "Apply config and language file changes automatically.", impact = "Invalid edits keep the last known good settings; edits made while this is off are not replayed when it is turned back on.")
        public boolean enabled = true;
        @ConfigDoc(value = "File watcher poll interval in milliseconds.", impact = "Accepted range is 250 through 60000 milliseconds.")
        public long pollIntervalMillis = 1000L;
        @ConfigDoc(value = "Quiet period before a changed file is applied, in milliseconds.", impact = "Accepted range is 250 through 60000 milliseconds.")
        public long cooldownMillis = 1500L;
        @ConfigDoc(value = "Show players with brimstone.config an action bar notice when file changes are applied or rejected.", impact = "The console always logs the outcome.")
        public boolean notifyOperators = true;
    }

    public static final class Debug {
        @ConfigDoc(value = "Allow /brimstone debug dump upload=true to upload reports to mclo.gs.", impact = "Reports are always saved locally; uploads happen only when an operator asks for one.")
        public boolean uploadEnabled = false;
    }

    public static final class Gameplay {
        @ConfigDoc(value = "Register crafting recipes for launchers with a crafting count above zero.", impact = "Disabling removes the recipes on the next install.")
        public boolean craftingEnabled = true;
        @ConfigDoc(value = "Delay between launches by one player, in ticks.", impact = "Accepted range is 0 through 72000; brimstone.bypass.cooldown skips it.")
        public int cooldownTicks = 30;
        @ConfigDoc(value = "Default fuel item spent on each launch.", impact = "A definition with an empty fuelMaterial uses this item.")
        public String fuelMaterial = "GUNPOWDER";
        @ConfigDoc(value = "Default number of fuel items spent on each launch.", impact = "Accepted range is 0 through 64; a definition fuelCount of -1 uses this value.")
        public int fuelCount = 1;
    }

    public static final class Access {
        @ConfigDoc(value = "World filter mode: ALL, ALLOWLIST, or DENYLIST.", impact = "ALLOWLIST permits only the listed worlds; DENYLIST blocks them.")
        public String worldMode = "ALL";
        @ConfigDoc(value = "Exact world names used by the world filter.", impact = "At most 128 unique, nonblank names of up to 128 characters.")
        public List<String> worlds = new ArrayList<>();
        @ConfigDoc(value = "WorldGuard checks: AUTO, REQUIRED, or DISABLED.", impact = "REQUIRED denies launches while WorldGuard is missing; AUTO checks only when it is installed.")
        public String worldGuard = "AUTO";
    }

    public static final class Limits {
        @ConfigDoc(value = "Live launched TNT per player.", impact = "Accepted range is 0 through 10000; zero denies new launches.")
        public int tntPerPlayer = 8;
        @ConfigDoc(value = "Live launched TNT per world.", impact = "Accepted range is 0 through 10000; zero denies new launches.")
        public int tntPerWorld = 64;
        @ConfigDoc(value = "Live launched TNT on the server.", impact = "Accepted range is 0 through 10000; zero denies new launches.")
        public int tntPerServer = 256;
        @ConfigDoc(value = "Live creepers and fireworks spawned by one player's TNT.", impact = "Accepted range is 0 through 50000.")
        public int secondaryPerPlayer = 16;
        @ConfigDoc(value = "Live creepers and fireworks spawned by TNT in one world.", impact = "Accepted range is 0 through 50000.")
        public int secondaryPerWorld = 128;
        @ConfigDoc(value = "Live creepers and fireworks spawned by TNT on the server.", impact = "Accepted range is 0 through 50000.")
        public int secondaryPerServer = 512;
    }

    public static final class Effects {
        @ConfigDoc(value = "Ticks between particle trail pulses.", impact = "Accepted range is 1 through 200; sound timing is independent.")
        public int trailIntervalTicks = 2;
        @ConfigDoc(value = "Particles in each trail pulse.", impact = "Accepted range is 0 through 256.")
        public int trailParticles = 4;
        @ConfigDoc(value = "Particles in each explosion burst.", impact = "Accepted range is 0 through 4096.")
        public int burstParticles = 60;
        @ConfigDoc(value = "Particles Brimstone may emit per tick across the server.", impact = "Accepted range is 0 through 65536; output beyond the budget is suppressed.")
        public int particlesPerTick = 4096;
        @ConfigDoc(value = "Ticks before a secondary creeper or firework is removed.", impact = "Accepted range is 1 through 72000.")
        public int secondaryLifetimeTicks = 600;
    }

    public static final class Definition {
        @ConfigDoc(value = "Built-in effect: notch, creeper, firework, love, dj, rainbow, or water.", impact = "The effect sets the trail, sound, and payload.")
        public String effect = "notch";
        @ConfigDoc(value = "Item display name; accepts & color codes.", impact = "At most 256 characters. Renaming does not change the persistent ID.")
        public String name = "&6Notch TNT";
        @ConfigDoc(value = "Item lore lines; accept & color codes.", impact = "At most 16 lines of up to 512 characters each.")
        public List<String> lore = new ArrayList<>(List.of("&7Left-click the air to launch."));
        @ConfigDoc(value = "Launch speed in blocks per tick.", impact = "Accepted range is 0 through 4.")
        public double velocity = 1.5;
        @ConfigDoc(value = "Explosion radius.", impact = "Accepted range is 0 through 16; zero still runs the effect payload.")
        public float radius = 3.0F;
        @ConfigDoc(value = "Set fire to blocks around the explosion.", impact = "Protection checks still apply.")
        public boolean fire = false;
        @ConfigDoc(value = "Fuse length in ticks.", impact = "Accepted range is 1 through 1200.")
        public int fuseTicks = 80;
        @ConfigDoc(value = "Ingredient combined with one TNT in the crafting recipe.", impact = "Must be an item material.")
        public String craftingMaterial = "TNT";
        @ConfigDoc(value = "Number of ingredient items in the recipe.", impact = "Accepted range is 0 through 8; zero omits the recipe.")
        public int craftingCount = 8;
        @ConfigDoc(value = "Fuel item for this launcher.", impact = "Empty uses gameplay.fuelMaterial.")
        public String fuelMaterial = "";
        @ConfigDoc(value = "Fuel items spent on each launch.", impact = "Accepted range is -1 through 64; -1 uses gameplay.fuelCount.")
        public int fuelCount = -1;
    }
}
