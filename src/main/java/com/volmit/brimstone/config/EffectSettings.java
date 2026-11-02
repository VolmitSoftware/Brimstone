package com.volmit.brimstone.config;

import java.util.Objects;

public record EffectSettings(int trailIntervalTicks, int trailParticles, int burstParticles, int particlesPerTick,
                             int secondaryLifetimeTicks) {
    public EffectSettings {
        validate(trailIntervalTicks, 1, 200, "trailIntervalTicks");
        validate(trailParticles, 0, 256, "trailParticles");
        validate(burstParticles, 0, 4096, "burstParticles");
        validate(particlesPerTick, 0, 65536, "particlesPerTick");
        validate(secondaryLifetimeTicks, 1, 72000, "secondaryLifetimeTicks");
    }

    public static EffectSettings from(BrimstoneConfig.Effects source) {
        Objects.requireNonNull(source, "effects");
        return new EffectSettings(source.trailIntervalTicks, source.trailParticles, source.burstParticles, source.particlesPerTick,
                source.secondaryLifetimeTicks);
    }

    private static void validate(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("effects." + field + " must be between " + minimum + " and " + maximum);
        }
    }
}
