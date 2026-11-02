package com.volmit.brimstone.protection;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.entity.EntityType;
import com.sk89q.worldedit.world.entity.EntityTypes;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldguard.protection.regions.RegionType;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

public final class WorldGuardProtection {
    public LaunchContext capture(Player player, boolean worldBypass) {
        WorldGuardPlugin plugin = WorldGuardPlugin.inst();
        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        boolean bypass = WorldGuard.getInstance().getPlatform().getSessionManager()
                .hasBypass(localPlayer, BukkitAdapter.adapt(player.getWorld()));
        return new LaunchContext(player.getUniqueId(), player.getName(), List.of(plugin.getGroups(player)),
                player.getWorld().getUID(), worldBypass, bypass);
    }

    public boolean allows(Location location, LaunchContext context, ProtectionKind kind) {
        if (context.regionBypass() && context.worldId().equals(location.getWorld().getUID())) {
            return true;
        }
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        ApplicableRegionSet regions = query.getApplicableRegions(BukkitAdapter.adapt(location));
        return allows(regions, new LaunchAssociation(context), kind);
    }

    static boolean allows(ApplicableRegionSet regions, RegionAssociable actor, ProtectionKind kind) {
        StateFlag.State build = StateFlag.denyToNone(regions.queryState(actor, Flags.BUILD));
        if (!StateFlag.test(StateFlag.combine(build, regions.queryState(actor, Flags.TNT)))) {
            return false;
        }
        return switch (kind) {
            case TNT -> true;
            case CREEPER -> allowsCreeper(regions, actor);
            case FIREWORK -> notDenied(regions, actor, Flags.FIREWORK_DAMAGE);
            case FIRE -> notDenied(regions, actor, Flags.FIRE_SPREAD) && notDenied(regions, actor, Flags.LIGHTER);
            case PLAYER_DAMAGE -> notDenied(regions, actor, Flags.PVP);
            case ANIMAL_DAMAGE -> notDenied(regions, actor, Flags.DAMAGE_ANIMALS);
            case VEHICLE_DAMAGE -> notDenied(regions, actor, Flags.DESTROY_VEHICLE);
        };
    }

    public boolean uniform(World world, BoundingBox bounds, LaunchContext context) {
        if (context.regionBypass() && context.worldId().equals(world.getUID())) {
            return true;
        }
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (manager == null) {
            return true;
        }
        ProtectedRegion area = new ProtectedCuboidRegion("brimstone-explosion", true,
                BlockVector3.at(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ()),
                BlockVector3.at(bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ()));
        for (ProtectedRegion region : manager.getApplicableRegions(area)) {
            if (region.getType() != RegionType.GLOBAL) {
                return false;
            }
        }
        return true;
    }

    private static boolean notDenied(ApplicableRegionSet regions, RegionAssociable actor, StateFlag flag) {
        return regions.queryState(actor, flag) != StateFlag.State.DENY;
    }

    private static boolean allowsCreeper(ApplicableRegionSet regions, RegionAssociable actor) {
        if (!notDenied(regions, actor, Flags.CREEPER_EXPLOSION) || !notDenied(regions, actor, Flags.MOB_SPAWNING)) {
            return false;
        }
        Set<EntityType> denied = regions.queryValue(actor, Flags.DENY_SPAWN);
        return denied == null || !denied.contains(EntityTypes.CREEPER);
    }

    record LaunchAssociation(LaunchContext context) implements RegionAssociable {
        @Override
        public Association getAssociation(List<ProtectedRegion> regions) {
            boolean member = false;
            for (ProtectedRegion region : regions) {
                ProtectedRegion current = region;
                while (current != null) {
                    if (contains(current.getOwners())) {
                        return Association.OWNER;
                    }
                    if (!member && contains(current.getMembers())) {
                        member = true;
                    }
                    current = current.getParent();
                }
            }
            return member ? Association.MEMBER : Association.NON_MEMBER;
        }

        private boolean contains(DefaultDomain domain) {
            if (domain.contains(context.owner()) || domain.contains(context.name())) {
                return true;
            }
            for (String group : context.groups()) {
                if (domain.getGroups().contains(group)) {
                    return true;
                }
            }
            return false;
        }
    }
}
