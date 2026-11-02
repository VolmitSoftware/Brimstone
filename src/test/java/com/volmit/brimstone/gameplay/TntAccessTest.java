package com.volmit.brimstone.gameplay;

import com.volmit.brimstone.config.BrimstoneConfig;
import com.volmit.brimstone.config.RuntimeConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TntAccessTest {
    private BrimstoneConfig source;
    private AtomicReference<RuntimeConfig> configuration;
    private TntService service;
    private List<String> feedback;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        source = new BrimstoneConfig();
        configuration = new AtomicReference<>(RuntimeConfig.from(source));
        feedback = new ArrayList<>();
        service = new TntService(plugin, configuration::get, (player, key) -> feedback.add(key));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void automaticCrafterCannotProduceLauncherItems() {
        CrafterCraftEvent tagged = mock(CrafterCraftEvent.class);
        CraftingRecipe launcherRecipe = recipe(service.createItem("notch", 1));
        when(tagged.getRecipe()).thenReturn(launcherRecipe);
        service.onAutomatedCraft(tagged);
        verify(tagged).setCancelled(true);

        CrafterCraftEvent vanilla = mock(CrafterCraftEvent.class);
        CraftingRecipe vanillaRecipe = recipe(new ItemStack(Material.TNT));
        when(vanilla.getRecipe()).thenReturn(vanillaRecipe);
        service.onAutomatedCraft(vanilla);
        verify(vanilla, never()).setCancelled(true);
    }

    @Test
    void taggedItemsCannotBePlacedOrDispensedAsVanillaTnt() {
        ItemStack launcher = service.createItem("notch", 1);
        BlockPlaceEvent place = mock(BlockPlaceEvent.class);
        when(place.getItemInHand()).thenReturn(launcher);
        service.onPlace(place);
        verify(place).setCancelled(true);
        BlockDispenseEvent dispense = mock(BlockDispenseEvent.class);
        when(dispense.getItem()).thenReturn(launcher);
        service.onDispense(dispense);
        verify(dispense).setCancelled(true);
    }

    @Test
    void vanillaTntPlacementAndDispensingRemainAvailable() {
        ItemStack vanilla = new ItemStack(Material.TNT);
        BlockPlaceEvent place = mock(BlockPlaceEvent.class);
        when(place.getItemInHand()).thenReturn(vanilla);
        service.onPlace(place);
        verify(place, never()).setCancelled(true);
        BlockDispenseEvent dispense = mock(BlockDispenseEvent.class);
        when(dispense.getItem()).thenReturn(vanilla);
        service.onDispense(dispense);
        verify(dispense, never()).setCancelled(true);
    }

    @Test
    void removedDefinitionCannotBeCraftedFromCachedRecipe() {
        Recipe cached = recipe(service.createItem("notch", 1));
        source.definitions.remove("notch");
        configuration.set(RuntimeConfig.from(source));
        Player player = mock(Player.class);
        when(player.hasPermission("brimstone.craft")).thenReturn(true);
        when(player.hasPermission("brimstone.craft.notch")).thenReturn(true);
        CraftItemEvent event = craft(cached, player);
        service.onCraft(event);
        verify(event).setCancelled(true);
        assertEquals(List.of("craft.no-permission"), feedback);
    }

    @Test
    void ordinaryAndShiftCraftDenialsLeaveIngredientsUntouched() {
        Recipe recipe = recipe(service.createItem("notch", 1));
        Player player = mock(Player.class);
        when(player.hasPermission("brimstone.craft")).thenReturn(true);
        when(player.hasPermission("brimstone.craft.notch")).thenReturn(false);
        for (boolean shift : List.of(false, true)) {
            CraftItemEvent event = craft(recipe, player);
            CraftingInventory ingredients = mock(CraftingInventory.class);
            when(event.getInventory()).thenReturn(ingredients);
            when(event.isShiftClick()).thenReturn(shift);
            service.onCraft(event);
            verify(event).setCancelled(true);
            verifyNoInteractions(ingredients);
        }
        assertEquals(List.of("craft.no-permission", "craft.no-permission"), feedback);
    }

    @Test
    void disabledCraftingRejectsPreviouslyPreparedLauncherRecipe() {
        Recipe cached = recipe(service.createItem("notch", 1));
        source.gameplay.craftingEnabled = false;
        configuration.set(RuntimeConfig.from(source));
        CraftItemEvent event = craft(cached, mock(Player.class));
        service.onCraft(event);
        verify(event).setCancelled(true);

        CraftItemEvent vanilla = craft(recipe(new ItemStack(Material.TNT)), mock(Player.class));
        service.onCraft(vanilla);
        verify(vanilla, never()).setCancelled(true);
    }

    private CraftingRecipe recipe(ItemStack result) {
        CraftingRecipe recipe = mock(CraftingRecipe.class);
        when(recipe.getResult()).thenReturn(result);
        return recipe;
    }

    private CraftItemEvent craft(Recipe recipe, Player player) {
        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getRecipe()).thenReturn(recipe);
        when(event.getWhoClicked()).thenReturn(player);
        return event;
    }
}
