package com.volmit.brimstone.config;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record AccessPolicy(WorldMode worldMode, Set<String> worlds, WorldGuardMode worldGuard) {
    public AccessPolicy {
        Objects.requireNonNull(worldMode, "access.worldMode");
        Objects.requireNonNull(worldGuard, "access.worldGuard");
        worlds = Set.copyOf(worlds);
    }

    public static AccessPolicy from(BrimstoneConfig.Access source) {
        Objects.requireNonNull(source, "access");
        if (source.worlds == null || source.worlds.size() > 128) {
            throw new IllegalArgumentException("access.worlds must contain at most 128 world names");
        }
        Set<String> worlds = new HashSet<>(source.worlds.size());
        for (String world : source.worlds) {
            if (world == null || world.isBlank() || world.length() > 128 || !worlds.add(world)) {
                throw new IllegalArgumentException("access.worlds must contain unique, nonblank names of at most 128 characters");
            }
        }
        return new AccessPolicy(mode(source.worldMode, WorldMode.class, "access.worldMode"), worlds,
                mode(source.worldGuard, WorldGuardMode.class, "access.worldGuard"));
    }

    public boolean allowedWorld(String world) {
        Objects.requireNonNull(world, "world");
        return switch (worldMode) {
            case ALL -> true;
            case ALLOWLIST -> worlds.contains(world);
            case DENYLIST -> !worlds.contains(world);
        };
    }

    private static <E extends Enum<E>> E mode(String value, Class<E> type, String path) {
        if (value == null) {
            throw new IllegalArgumentException(path + " must be a valid mode");
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid " + path + ": " + value, exception);
        }
    }

    public enum WorldMode {
        ALL, ALLOWLIST, DENYLIST
    }

    public enum WorldGuardMode {
        AUTO, REQUIRED, DISABLED
    }
}
