package com.volmit.brimstone.protection;

import com.volmit.brimstone.config.BrimstoneConfig;
import com.volmit.brimstone.config.RuntimeConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.FireworkExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtectionServiceTest {
    private JavaPlugin plugin;
    private BrimstoneConfig source;
    private AtomicReference<RuntimeConfig> configuration;
    private ProtectionService service;
    private World world;
    private LaunchContext context;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        source = new BrimstoneConfig();
        configuration = new AtomicReference<>(RuntimeConfig.from(source));
        service = new ProtectionService(plugin, configuration::get);
        world = world("arena");
        context = new LaunchContext(UUID.randomUUID(), "Launcher", List.of("builders"), world.getUID(), false, false);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void requiredProtectionFailsClosedWhenWorldGuardIsMissing() {
        Location location = new Location(world, 0, 80, 0);
        assertTrue(service.allows(location, context, ProtectionKind.TNT));
        source.access.worldGuard = "REQUIRED";
        install();
        assertFalse(service.allows(location, context, ProtectionKind.TNT));
        source.access.worldGuard = "DISABLED";
        install();
        assertTrue(service.allows(location, context, ProtectionKind.TNT));
    }

    @Test
    void reloadAppliesWorldPolicyToPreviouslyLaunchedEntities() {
        Entity entity = entity(new Location(world, 0, 80, 0));
        service.tag(entity, context, ProtectionKind.CREEPER);
        assertTrue(service.allows(entity.getLocation(), service.context(entity), ProtectionKind.CREEPER));
        source.access.worldMode = "DENYLIST";
        source.access.worlds = List.of("arena");
        install();
        assertFalse(service.allows(entity.getLocation(), service.context(entity), ProtectionKind.CREEPER));
        source.access.worlds = List.of("lobby");
        install();
        assertTrue(service.allows(entity.getLocation(), service.context(entity), ProtectionKind.CREEPER));
    }

    @Test
    void secondaryEntitiesPreserveLaunchWorldAndImmutablePermissionSnapshot() {
        List<String> groups = new ArrayList<>(List.of("builders", "staff"));
        LaunchContext captured = new LaunchContext(context.owner(), context.name(), groups, world.getUID(), true, true);
        groups.clear();
        Entity secondary = entity(new Location(world("destination"), 0, 80, 0));
        service.tag(secondary, captured, ProtectionKind.FIREWORK);
        assertEquals(captured, service.context(secondary));
        assertEquals(world.getUID(), service.context(secondary).worldId());
        assertEquals(List.of("builders", "staff"), service.context(secondary).groups());
        assertTrue(service.context(secondary).worldBypass());
        assertTrue(service.context(secondary).regionBypass());
    }

    @Test
    void nullOrIncompleteOwnershipCannotAuthorizeTaggedEffects() {
        Location location = new Location(world, 0, 80, 0);
        assertFalse(service.allows(location, null, ProtectionKind.TNT));
        assertFalse(service.allows(null, context, ProtectionKind.TNT));
        assertFalse(service.allows(new Location(null, 0, 80, 0), context, ProtectionKind.TNT));
        Entity sourceEntity = entity(location);
        sourceEntity.getPersistentDataContainer().set(new NamespacedKey(plugin, "protection-kind"),
                PersistentDataType.STRING, "TNT");
        assertNull(service.context(sourceEntity));
        EntityDamageByEntityEvent damage = damage(sourceEntity, entity(location));
        service.onDamage(damage);
        verify(damage).setCancelled(true);
    }

    @Test
    void taggedDamageChecksVictimPermissionEvenWhenSourceIsAllowed() {
        ProtectionService checked = spy(service);
        Location origin = new Location(world, 0, 80, 0);
        Location target = new Location(world, 4, 80, 0);
        Entity firework = entity(origin);
        checked.tag(firework, context, ProtectionKind.FIREWORK);
        Player victim = mock(Player.class);
        when(victim.getLocation()).thenReturn(target);
        doReturn(false).when(checked).allows(target, context, ProtectionKind.PLAYER_DAMAGE);
        EntityDamageByEntityEvent damage = damage(firework, victim);
        checked.onDamage(damage);
        verify(damage).setCancelled(true);
        verify(checked).allows(target, context, ProtectionKind.PLAYER_DAMAGE);
    }

    @Test
    void taggedDamageCannotCrossIntoDeniedWorld() {
        Location origin = new Location(world, 0, 80, 0);
        Entity creeper = entity(origin);
        service.tag(creeper, context, ProtectionKind.CREEPER);
        source.access.worldMode = "ALLOWLIST";
        source.access.worlds = List.of("arena");
        install();
        EntityDamageByEntityEvent damage = damage(creeper, entity(new Location(world("lobby"), 0, 80, 0)));
        service.onDamage(damage);
        verify(damage).setCancelled(true);
    }

    @Test
    void explosionFiltersProtectedBlocksWithoutCancellingAllowedBlast() {
        ProtectionService checked = spy(service);
        Location origin = new Location(world, 0, 80, 0);
        Entity tnt = entity(origin);
        checked.tag(tnt, context, ProtectionKind.TNT);
        Block allowed = mock(Block.class);
        Block denied = mock(Block.class);
        Location allowedLocation = new Location(world, 1, 80, 0);
        Location deniedLocation = new Location(world, 2, 80, 0);
        when(allowed.getLocation()).thenReturn(allowedLocation);
        when(denied.getLocation()).thenReturn(deniedLocation);
        doReturn(false).when(checked).allows(deniedLocation, context, ProtectionKind.TNT);
        doReturn(false).when(checked).uniform(any(), any(), any());
        List<Block> affected = new ArrayList<>(List.of(allowed, denied));
        EntityExplodeEvent explosion = mock(EntityExplodeEvent.class);
        when(explosion.getEntity()).thenReturn(tnt);
        when(explosion.getLocation()).thenReturn(origin);
        when(explosion.blockList()).thenReturn(affected);
        checked.onExplode(explosion);
        assertEquals(List.of(allowed), affected);
        verify(explosion, never()).setCancelled(true);
    }

    @Test
    void uniformExplosionAreaChecksOneBlockInsteadOfEveryBlock() {
        ProtectionService checked = spy(service);
        Location origin = new Location(world, 0, 80, 0);
        Entity tnt = entity(origin);
        checked.tag(tnt, context, ProtectionKind.TNT);
        Location first = new Location(world, 1, 80, 0);
        Location second = new Location(world, 2, 80, 0);
        Block firstBlock = mock(Block.class);
        Block secondBlock = mock(Block.class);
        when(firstBlock.getLocation()).thenReturn(first);
        when(secondBlock.getLocation()).thenReturn(second);
        List<Block> affected = new ArrayList<>(List.of(firstBlock, secondBlock));
        EntityExplodeEvent explosion = mock(EntityExplodeEvent.class);
        when(explosion.getEntity()).thenReturn(tnt);
        when(explosion.getLocation()).thenReturn(origin);
        when(explosion.blockList()).thenReturn(affected);
        checked.onExplode(explosion);
        assertEquals(List.of(firstBlock, secondBlock), affected);
        verify(checked, times(1)).allows(first, context, ProtectionKind.TNT);
        verify(checked, never()).allows(second, context, ProtectionKind.TNT);
        doReturn(false).when(checked).allows(first, context, ProtectionKind.TNT);
        checked.onExplode(explosion);
        assertTrue(affected.isEmpty());
        verify(explosion, never()).setCancelled(true);
    }

    @Test
    void vanillaEffectsRemainUntouchedEvenWhenWorldIsDenied() {
        source.access.worldMode = "ALLOWLIST";
        source.access.worlds = List.of();
        install();
        Entity vanilla = entity(new Location(world, 0, 80, 0));
        ExplosionPrimeEvent prime = new ExplosionPrimeEvent(vanilla, 4, true);
        service.onPrime(prime);
        assertFalse(prime.isCancelled());
        assertEquals(4, prime.getRadius());
        assertTrue(prime.getFire());
        EntityDamageByEntityEvent damage = damage(vanilla, entity(vanilla.getLocation()));
        service.onDamage(damage);
        verify(damage, never()).setCancelled(true);
        EntityExplodeEvent explosion = mock(EntityExplodeEvent.class);
        when(explosion.getEntity()).thenReturn(vanilla);
        service.onExplode(explosion);
        verify(explosion, never()).setCancelled(true);
        FireworkExplodeEvent firework = mock(FireworkExplodeEvent.class);
        Firework vanillaFirework = mock(Firework.class);
        PersistentDataContainer vanillaData = vanilla.getPersistentDataContainer();
        when(vanillaFirework.getPersistentDataContainer()).thenReturn(vanillaData);
        when(firework.getEntity()).thenReturn(vanillaFirework);
        service.onFirework(firework);
        verify(firework, never()).setCancelled(true);
    }

    private void install() {
        configuration.set(RuntimeConfig.from(source));
    }

    private World world(String name) {
        World result = mock(World.class);
        when(result.getName()).thenReturn(name);
        when(result.getUID()).thenReturn(UUID.randomUUID());
        return result;
    }

    private Entity entity(Location location) {
        Entity result = mock(Entity.class);
        PersistentDataContainer data = new ItemStack(Material.TNT).getItemMeta().getPersistentDataContainer();
        when(result.getPersistentDataContainer()).thenReturn(data);
        when(result.getLocation()).thenReturn(location);
        return result;
    }

    private EntityDamageByEntityEvent damage(Entity sourceEntity, Entity victim) {
        EntityDamageByEntityEvent result = mock(EntityDamageByEntityEvent.class);
        when(result.getDamager()).thenReturn(sourceEntity);
        when(result.getEntity()).thenReturn(victim);
        return result;
    }
}
