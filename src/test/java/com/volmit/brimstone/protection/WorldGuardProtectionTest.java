package com.volmit.brimstone.protection;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.domains.Association;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.RegionResultSet;
import com.sk89q.worldguard.protection.association.Associables;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGuardProtectionTest {
    private static final RegionAssociable MEMBER = Associables.constant(Association.MEMBER);
    private static final RegionAssociable NON_MEMBER = Associables.constant(Association.NON_MEMBER);

    @Test
    void unprotectedLocationsAllowEveryEffect() {
        ApplicableRegionSet regions = regions();
        for (ProtectionKind kind : ProtectionKind.values()) {
            assertTrue(WorldGuardProtection.allows(regions, NON_MEMBER, kind), kind.name());
        }
    }

    @Test
    void regionMembershipDecidesWhenTntIsUnset() {
        ApplicableRegionSet regions = regions(region());
        assertFalse(WorldGuardProtection.allows(regions, NON_MEMBER, ProtectionKind.TNT));
        assertTrue(WorldGuardProtection.allows(regions, MEMBER, ProtectionKind.TNT));
    }

    @Test
    void allowedTntGrantsNonMembersLikeWorldGuard() {
        ProtectedRegion arena = region();
        arena.setFlag(Flags.TNT, StateFlag.State.ALLOW);
        ApplicableRegionSet regions = regions(arena);
        assertTrue(WorldGuardProtection.allows(regions, NON_MEMBER, ProtectionKind.TNT));
        assertTrue(WorldGuardProtection.allows(regions, NON_MEMBER, ProtectionKind.CREEPER));
        assertTrue(WorldGuardProtection.allows(regions, NON_MEMBER, ProtectionKind.FIREWORK));
        assertTrue(WorldGuardProtection.allows(regions, NON_MEMBER, ProtectionKind.PLAYER_DAMAGE));
        assertTrue(WorldGuardProtection.allows(regions, NON_MEMBER, ProtectionKind.ANIMAL_DAMAGE));
    }

    @Test
    void deniedTntBlocksMembers() {
        ProtectedRegion region = region();
        region.setFlag(Flags.TNT, StateFlag.State.DENY);
        assertFalse(WorldGuardProtection.allows(regions(region), MEMBER, ProtectionKind.TNT));
    }

    @Test
    void effectFlagsDenyOnlyTheirOwnEffect() {
        ProtectedRegion arena = region();
        arena.setFlag(Flags.TNT, StateFlag.State.ALLOW);
        arena.setFlag(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY);
        arena.setFlag(Flags.FIREWORK_DAMAGE, StateFlag.State.DENY);
        arena.setFlag(Flags.PVP, StateFlag.State.DENY);
        arena.setFlag(Flags.DAMAGE_ANIMALS, StateFlag.State.DENY);
        arena.setFlag(Flags.DESTROY_VEHICLE, StateFlag.State.DENY);
        arena.setFlag(Flags.FIRE_SPREAD, StateFlag.State.DENY);
        ApplicableRegionSet regions = regions(arena);
        assertTrue(WorldGuardProtection.allows(regions, NON_MEMBER, ProtectionKind.TNT));
        for (ProtectionKind kind : List.of(ProtectionKind.CREEPER, ProtectionKind.FIREWORK, ProtectionKind.PLAYER_DAMAGE,
                ProtectionKind.ANIMAL_DAMAGE, ProtectionKind.VEHICLE_DAMAGE, ProtectionKind.FIRE)) {
            assertFalse(WorldGuardProtection.allows(regions, MEMBER, kind), kind.name());
        }
    }

    private static ProtectedRegion region() {
        return new ProtectedCuboidRegion("arena", BlockVector3.at(0, 0, 0), BlockVector3.at(16, 16, 16));
    }

    private static ApplicableRegionSet regions(ProtectedRegion... regions) {
        return new RegionResultSet(new ArrayList<>(List.of(regions)), null);
    }
}
