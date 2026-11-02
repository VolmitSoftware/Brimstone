package com.volmit.brimstone.gameplay;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.brimstone.config.BrimstoneConfig;
import com.volmit.brimstone.config.RuntimeConfig;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TntRecipeReloadTest {
    private BrimstoneConfig source;
    private AtomicReference<RuntimeConfig> configuration;
    private Server server;
    private TntService service;
    private MockedStatic<FoliaScheduler> scheduler;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        JavaPlugin plugin = spy(MockBukkit.createMockPlugin());
        server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.addRecipe(any(Recipe.class))).thenReturn(true);
        source = new BrimstoneConfig();
        configuration = new AtomicReference<>(RuntimeConfig.from(source));
        service = new TntService(plugin, configuration::get, (player, key) -> {});
        scheduler = mockStatic(FoliaScheduler.class);
        scheduler.when(() -> FoliaScheduler.runGlobal(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        });
        service.register();
        verify(server, times(7)).addRecipe(any(Recipe.class));
        clearInvocations(server);
    }

    @AfterEach
    void tearDown() {
        try {
            if (scheduler != null) {
                scheduler.close();
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void gameplayAndLanguageReloadsDoNotTouchTheRecipeRegistry() {
        source.general.language = "de_DE";
        source.limits.tntPerPlayer = 1;
        source.access.worlds.add("world");
        source.effects.particlesPerTick = 10;
        source.definitions.get("notch").radius = 2;
        source.definitions.get("notch").fuelCount = 3;
        reload();
        reload();
        verify(server, never()).removeRecipe(any(NamespacedKey.class));
        verify(server, never()).addRecipe(any(Recipe.class));
    }

    @Test
    void ingredientsAndResultMetadataStillRefreshRecipes() {
        source.definitions.get("notch").craftingCount = 2;
        reload();
        verify(server, times(7)).removeRecipe(any(NamespacedKey.class));
        verify(server, times(7)).addRecipe(any(Recipe.class));
        clearInvocations(server);
        source.definitions.get("notch").lore.add("Changed recipe result");
        reload();
        verify(server, times(7)).removeRecipe(any(NamespacedKey.class));
        verify(server, times(7)).addRecipe(any(Recipe.class));
    }

    @Test
    void disablingCraftingRemovesRecipesOnlyOnceAndReenablingRestoresThem() {
        source.gameplay.craftingEnabled = false;
        reload();
        reload();
        verify(server, times(7)).removeRecipe(any(NamespacedKey.class));
        verify(server, never()).addRecipe(any(Recipe.class));
        source.gameplay.craftingEnabled = true;
        reload();
        verify(server, times(7)).addRecipe(any(Recipe.class));
    }

    @Test
    void readinessWaitsForQueuedRecipeWorkAndPublishesRegisteredCounts() {
        assertTrue(service.ready());
        assertEquals(7, service.registeredRecipeCount());
        AtomicReference<Runnable> queued = new AtomicReference<>();
        scheduler.when(() -> FoliaScheduler.runGlobal(any(JavaPlugin.class), any(Runnable.class))).thenAnswer(invocation -> {
            queued.set(invocation.getArgument(1));
            return true;
        });
        source.gameplay.craftingEnabled = false;
        reload();
        assertFalse(service.ready());
        assertEquals(7, service.registeredRecipeCount());
        queued.get().run();
        assertTrue(service.ready());
        assertEquals(0, service.registeredRecipeCount());
        service.updateRecipes();
        assertFalse(service.ready());
        queued.get().run();
        assertTrue(service.ready());
        service.shutdown();
        assertFalse(service.ready());
        assertEquals(0, service.registeredRecipeCount());
    }

    @Test
    void rejectedRecipeRegistrationStaysUnreadyAndNextReloadRetries() {
        source.definitions.get("notch").craftingCount = 2;
        when(server.addRecipe(any(Recipe.class))).thenReturn(true, false);
        assertThrows(IllegalStateException.class, this::reload);
        assertFalse(service.ready());
        assertEquals(1, service.registeredRecipeCount());
        source.definitions.get("notch").craftingCount = 1;
        when(server.addRecipe(any(Recipe.class))).thenReturn(true);
        reload();
        assertTrue(service.ready());
        assertEquals(7, service.registeredRecipeCount());
    }

    private void reload() {
        configuration.set(RuntimeConfig.from(source));
        service.updateRecipes();
    }
}
