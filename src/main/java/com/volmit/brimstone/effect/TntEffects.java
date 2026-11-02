package com.volmit.brimstone.effect;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.brimstone.config.RuntimeConfig;
import com.volmit.brimstone.diagnostics.EffectDiagnostics.SecondarySuppression;
import com.volmit.brimstone.gameplay.EntityBudget;
import com.volmit.brimstone.gameplay.ManagedEffects;
import com.volmit.brimstone.protection.LaunchContext;
import com.volmit.brimstone.protection.ProtectionKind;
import com.volmit.brimstone.protection.ProtectionService;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Firework;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class TntEffects {
    private static final Color[] COLORS = {Color.AQUA, Color.BLUE, Color.YELLOW, Color.FUCHSIA, Color.GRAY,
            Color.WHITE, Color.TEAL, Color.RED, Color.PURPLE, Color.GREEN, Color.ORANGE, Color.OLIVE,
            Color.NAVY, Color.MAROON, Color.LIME};
    private static final FireworkEffect.Type[] FIREWORK_SHAPES = {FireworkEffect.Type.BALL,
            FireworkEffect.Type.BURST, FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR};
    private final JavaPlugin plugin;
    private final Supplier<RuntimeConfig> configuration;
    private final ProtectionService protection;
    private final ManagedEffects managed;

    public TntEffects(JavaPlugin plugin, Supplier<RuntimeConfig> configuration, ProtectionService protection, ManagedEffects managed) {
        this.plugin = plugin;
        this.configuration = configuration;
        this.protection = protection;
        this.managed = managed;
    }

    public void launch(TNTPrimed entity, String effect) {
        if (effect.equals("creeper") || effect.equals("firework")) {
            entity.setVelocity(entity.getVelocity().setY(entity.getVelocity().getY() + 1).multiply(1.5));
        }
        Sound sound = switch (effect) {
            case "creeper", "firework" -> Sound.ENTITY_FIREWORK_ROCKET_LAUNCH;
            case "love" -> Sound.ENTITY_GENERIC_EXPLODE;
            case "dj" -> Sound.BLOCK_PISTON_EXTEND;
            case "rainbow" -> Sound.ENTITY_HORSE_JUMP;
            case "water" -> Sound.ENTITY_GENERIC_SWIM;
            default -> Sound.ENTITY_TNT_PRIMED;
        };
        float pitch = switch (effect) {
            case "love", "water" -> 0.5f;
            case "dj", "rainbow" -> 0.6f;
            default -> 0.8f;
        };
        Location location = entity.getLocation();
        location.getWorld().playSound(location, sound, 1, pitch);
        if (effect.equals("creeper") || effect.equals("firework")) {
            emit(location, Particle.LARGE_SMOKE, particles(configuration.get().effects().trailParticles()), 0.2, 0.2);
        } else {
            trail(entity, effect);
        }
    }

    public void trail(TNTPrimed entity, String effect) {
        Location location = entity.getLocation().add(0, 1, 0);
        Particle particle = switch (effect) {
            case "creeper" -> Particle.CLOUD;
            case "firework" -> Particle.FIREWORK;
            case "love" -> Particle.HEART;
            case "dj" -> Particle.NOTE;
            case "water" -> Particle.DRIPPING_WATER;
            case "rainbow" -> Particle.DUST;
            default -> Particle.FLAME;
        };
        int count = particles(configuration.get().effects().trailParticles());
        if (count > 0) {
            if (particle == Particle.DUST) {
                rainbow(location, count, 0.2, 0.2, 1);
            } else if (effect.equals("notch") || effect.equals("water")) {
                emit(location, particle, (count + 1) / 2, 0.2, 0.2);
                emit(location, effect.equals("notch") ? Particle.SMOKE : Particle.SPLASH, count / 2, 0.2, 0.2);
            } else {
                location.getWorld().spawnParticle(particle, location, count, 0.2, 0.2, 0.2, 0.01);
            }
        }
    }

    public int soundInterval(String effect) {
        return switch (effect) {
            case "dj" -> DjMelody.STEP_TICKS;
            case "creeper" -> 5;
            case "rainbow" -> 1;
            default -> 0;
        };
    }

    public void sound(TNTPrimed entity, String effect, int elapsedTicks) {
        Location location = entity.getLocation().add(0, 1, 0);
        switch (effect) {
            case "dj" -> {
                float pitch = DjMelody.pitch(elapsedTicks - DjMelody.STEP_TICKS);
                if (pitch > 0) {
                    location.getWorld().playSound(location, Sound.BLOCK_NOTE_BLOCK_HARP, 4, pitch);
                }
            }
            case "creeper" -> location.getWorld().playSound(location, Sound.ENTITY_CREEPER_PRIMED, 3, 1);
            case "rainbow" -> location.getWorld().playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 3,
                    Math.clamp((float) entity.getFuseTicks() / Math.max(1, entity.getTicksLived()), 0.5f, 2));
            default -> { }
        }
    }

    public void explode(Location location, String effect, float radius, LaunchContext context, long generation) {
        if (effect.equals("creeper")) {
            firework(location.clone().add(0, 3, 0), true, context, generation);
            int count = Math.min(8, Math.max(1, (int) Math.ceil(radius / 2)));
            for (int index = 0; index < count; index++) {
                double angle = index * Math.PI * 2 / count;
                Location spawn = location.clone().add(Math.cos(angle) * Math.min(radius, 4), 1, Math.sin(angle) * Math.min(radius, 4));
                EntityBudget.Ticket ticket = reserve(spawn, context, generation);
                if (ticket != null && !FoliaScheduler.runRegion(plugin, spawn, () -> spawnCreeper(spawn, context, ticket), 2)) {
                    managed.budget().diagnostics().secondarySuppressed(SecondarySuppression.SCHEDULER);
                    managed.budget().release(ticket);
                }
            }
        } else if (effect.equals("firework")) {
            for (int index = 0; index < 5; index++) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                double angle = random.nextDouble(Math.PI * 2);
                double distance = Math.sqrt(random.nextDouble()) * radius * 1.5;
                Location spawn = location.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                EntityBudget.Ticket ticket = reserve(spawn, context, generation);
                if (ticket != null && !FoliaScheduler.runRegion(plugin, spawn, () -> spawnFirework(spawn, false, context, ticket))) {
                    managed.budget().diagnostics().secondarySuppressed(SecondarySuppression.SCHEDULER);
                    managed.budget().release(ticket);
                }
            }
        } else if (effect.equals("rainbow")) {
            int count = particles(configuration.get().effects().burstParticles());
            if (count > 0) {
                rainbow(location, count, radius * 0.35, 1.5, 1.5f);
            }
        } else {
            Particle particle = switch (effect) {
                case "love" -> Particle.HEART;
                case "dj" -> Particle.NOTE;
                case "water" -> Particle.SPLASH;
                default -> Particle.FLAME;
            };
            int count = particles(configuration.get().effects().burstParticles());
            if (count > 0) {
                if (effect.equals("love")) {
                    emit(location, Particle.HEART, (count + 1) / 2, radius * 0.35, 1.5);
                    emit(location, Particle.CRIT, count / 2, radius * 0.35, 1.5);
                } else {
                    emit(location, particle, count, radius * 0.35, 1.5);
                }
            }
            if (effect.equals("water")) {
                location.getWorld().playSound(location, Sound.ENTITY_GENERIC_SWIM, 1, 1);
            }
        }
    }

    private void spawnCreeper(Location location, LaunchContext context, EntityBudget.Ticket ticket) {
        if (!allowed(location, context, ticket, ProtectionKind.CREEPER)) {
            managed.budget().release(ticket);
            return;
        }
        try {
            Creeper entity = location.getWorld().spawn(location, Creeper.class, creeper -> {
                creeper.setPowered(true);
                protection.tag(creeper, context, ProtectionKind.CREEPER);
                managed.prepare(creeper, ticket);
            });
            if (!managed.track(entity, ticket, null)) {
                managed.budget().diagnostics().secondarySuppressed(SecondarySuppression.TRACKING_FAILED);
            }
        } catch (RuntimeException exception) {
            failed(ticket, exception);
        }
    }

    private void firework(Location location, boolean creeper, LaunchContext context, long generation) {
        EntityBudget.Ticket ticket = reserve(location, context, generation);
        if (ticket != null && !FoliaScheduler.runRegion(plugin, location, () -> spawnFirework(location, creeper, context, ticket))) {
            managed.budget().diagnostics().secondarySuppressed(SecondarySuppression.SCHEDULER);
            managed.budget().release(ticket);
        }
    }

    private void spawnFirework(Location location, boolean creeper, LaunchContext context, EntityBudget.Ticket ticket) {
        if (!allowed(location, context, ticket, ProtectionKind.FIREWORK)) {
            managed.budget().release(ticket);
            return;
        }
        try {
            Firework entity = location.getWorld().spawn(location, Firework.class, firework -> {
                configureFirework(firework, creeper);
                protection.tag(firework, context, ProtectionKind.FIREWORK);
                managed.prepare(firework, ticket);
            });
            if (!managed.track(entity, ticket, null)) {
                managed.budget().diagnostics().secondarySuppressed(SecondarySuppression.TRACKING_FAILED);
            }
            if (creeper && managed.current(ticket)) {
                entity.detonate();
            }
        } catch (RuntimeException exception) {
            failed(ticket, exception);
        }
    }

    private EntityBudget.Ticket reserve(Location location, LaunchContext context, long generation) {
        EntityBudget.Admission admission = managed.budget().reserve(context.owner(), location.getWorld().getUID(), EntityBudget.Kind.SECONDARY,
                configuration.get().limits(), generation);
        if (admission.ticket() == null) {
            managed.budget().diagnostics().secondarySuppressed(admission.denial() == EntityBudget.Denial.STOPPED
                    ? SecondarySuppression.STOPPED : SecondarySuppression.QUOTA);
        }
        return admission.ticket();
    }

    private boolean allowed(Location location, LaunchContext context, EntityBudget.Ticket ticket, ProtectionKind kind) {
        SecondarySuppression reason;
        if (!managed.current(ticket)) {
            reason = SecondarySuppression.STOPPED;
        } else if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            reason = SecondarySuppression.UNLOADED_CHUNK;
        } else if (!protection.allows(location, context, kind)) {
            reason = SecondarySuppression.PROTECTION;
        } else {
            return true;
        }
        managed.budget().diagnostics().secondarySuppressed(reason);
        return false;
    }

    private int particles(int requested) {
        return managed.budget().particles(requested, configuration.get().effects().particlesPerTick());
    }

    private void failed(EntityBudget.Ticket ticket, RuntimeException exception) {
        managed.budget().diagnostics().secondarySuppressed(SecondarySuppression.SPAWN_FAILED);
        managed.budget().release(ticket);
        plugin.getLogger().log(Level.SEVERE, "Could not spawn secondary TNT effect in world " + ticket.world(), exception);
    }

    private void configureFirework(Firework firework, boolean creeper) {
        FireworkMeta meta = firework.getFireworkMeta();
        meta.setPower(creeper ? 3 : 0);
        if (creeper) {
            meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.CREEPER).withColor(Color.GREEN).flicker(true).build());
        } else {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int count = random.nextInt(1, 4);
            for (int index = 0; index < count; index++) {
                FireworkEffect.Builder builder = FireworkEffect.builder().with(FIREWORK_SHAPES[random.nextInt(FIREWORK_SHAPES.length)]);
                int colors = random.nextInt(1, 4);
                for (int color = 0; color < colors; color++) {
                    builder.withColor(COLORS[random.nextInt(COLORS.length)]);
                }
                builder.withFade(COLORS[random.nextInt(COLORS.length)]);
                boolean flicker = random.nextBoolean();
                meta.addEffect(builder.flicker(flicker).trail(!flicker).build());
            }
        }
        firework.setFireworkMeta(meta);
    }

    private void rainbow(Location location, int count, double horizontal, double vertical, float size) {
        int groups = Math.min(count, 7);
        for (int index = 0; index < groups; index++) {
            int amount = count / groups + (index < count % groups ? 1 : 0);
            location.getWorld().spawnParticle(Particle.DUST, location, amount, horizontal, vertical, horizontal, 0,
                    new Particle.DustOptions(randomColor(), size));
        }
    }

    private void emit(Location location, Particle particle, int count, double horizontal, double vertical) {
        if (count > 0) {
            location.getWorld().spawnParticle(particle, location, count, horizontal, vertical, horizontal, 0.01);
        }
    }

    private static Color randomColor() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return Color.fromRGB(random.nextInt(125, 256), random.nextInt(125, 256), random.nextInt(125, 256));
    }
}
