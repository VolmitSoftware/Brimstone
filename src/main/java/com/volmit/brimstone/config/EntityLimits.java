package com.volmit.brimstone.config;

import java.util.Objects;

public record EntityLimits(int tntPerPlayer, int tntPerWorld, int tntPerServer,
                           int secondaryPerPlayer, int secondaryPerWorld, int secondaryPerServer) {
    public EntityLimits {
        validate(tntPerPlayer, 10000, "tntPerPlayer");
        validate(tntPerWorld, 10000, "tntPerWorld");
        validate(tntPerServer, 10000, "tntPerServer");
        validate(secondaryPerPlayer, 50000, "secondaryPerPlayer");
        validate(secondaryPerWorld, 50000, "secondaryPerWorld");
        validate(secondaryPerServer, 50000, "secondaryPerServer");
    }

    public static EntityLimits from(BrimstoneConfig.Limits source) {
        Objects.requireNonNull(source, "limits");
        return new EntityLimits(source.tntPerPlayer, source.tntPerWorld, source.tntPerServer,
                source.secondaryPerPlayer, source.secondaryPerWorld, source.secondaryPerServer);
    }

    private static void validate(int value, int maximum, String field) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException("limits." + field + " must be between 0 and " + maximum);
        }
    }
}
