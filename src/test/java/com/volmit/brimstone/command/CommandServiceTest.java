package com.volmit.brimstone.command;

import art.arcane.volmlib.util.diagnostics.BukkitDebugDump;
import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorRoute;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.volmlib.util.localization.MessageArgs;
import com.volmit.brimstone.Brimstone;
import com.volmit.brimstone.gameplay.TntService;
import com.volmit.brimstone.localization.LanguageService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class CommandServiceTest {
    private static final Map<String, List<String>> EXPECTED_PERMISSIONS = Map.ofEntries(
            Map.entry("status", List.of("brimstone.command")),
            Map.entry("list", List.of("brimstone.command")),
            Map.entry("version", List.of("brimstone.command")),
            Map.entry("give", List.of("brimstone.give")),
            Map.entry("clear", List.of("brimstone.clear")),
            Map.entry("config", List.of("brimstone.config")),
            Map.entry("language", List.of("brimstone.language.self")),
            Map.entry("debug", List.of("brimstone.command", "brimstone.debug")),
            Map.entry("debug dump", List.of("brimstone.debug")),
            Map.entry("debug version", List.of("brimstone.command")));

    private Brimstone plugin;
    private CommandSender sender;
    private BukkitDebugDump dump;
    private TntService tnt;
    private CommandService commands;

    @BeforeEach
    void prepare() {
        plugin = mock(Brimstone.class);
        sender = mock(CommandSender.class);
        dump = mock(BukkitDebugDump.class);
        tnt = mock(TntService.class);
        when(plugin.tnt()).thenReturn(tnt);
        LanguageService language = mock(LanguageService.class);
        when(plugin.language()).thenReturn(language);
        when(language.directorResolver()).thenReturn(DirectorTextResolver.ENGLISH);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BrimstoneCommandsTest"));
        when(plugin.debugDump()).thenReturn(dump);
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("Brimstone", "2.0.0", Brimstone.class.getName()));
        commands = new CommandService(plugin);
    }

    @Test
    void dumpDefaultsToLocalAndAcceptsExplicitUpload() {
        when(sender.hasPermission("brimstone.debug")).thenReturn(true);
        commands.onCommand(sender, null, "brimstone", new String[]{"debug", "dump"});
        commands.onCommand(sender, null, "brimstone", new String[]{"debug", "dump", "true"});
        verify(dump).request(sender, false);
        verify(dump).request(sender, true);
    }

    @Test
    void versionIsAvailableWithoutDumpPermissionAndAliasIsHidden() {
        when(sender.hasPermission("brimstone.command")).thenReturn(true);
        commands.onCommand(sender, null, "brimstone", new String[]{"debug", "version"});
        commands.onCommand(sender, null, "brimstone", new String[]{"version"});
        verify(plugin, times(2)).send(eq(sender), eq("version"), any(MessageArgs.class));
        assertEquals(List.of("version"), commands.onTabComplete(sender, null, "brimstone", new String[]{"debug", ""}));
        assertFalse(commands.onTabComplete(sender, null, "brimstone", new String[]{""}).contains("version"));
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new BrimstoneCommands(plugin));
        DirectorMiniMenu.DirectorHelpPage help = DirectorMiniMenu.resolveHelp(engine, List.of()).orElseThrow();
        assertFalse(help.entries().stream().anyMatch(node -> node.getDescriptor().getName().equals("version")));
    }

    @Test
    void unauthorizedDumpDoesNotReachReportServiceOrCompletion() {
        when(sender.hasPermission("brimstone.command")).thenReturn(true);
        commands.onCommand(sender, null, "brimstone", new String[]{"debug", "dump", "true"});
        verify(plugin).send(sender, "no-permission");
        verify(dump, never()).request(any(CommandSender.class), anyBoolean());
        assertEquals(List.of(), commands.onTabComplete(sender, null, "brimstone", new String[]{"debug", "dump", ""}));
    }

    @Test
    void everyDirectorPathIsGatedByTheCommandItRoutesTo() {
        List<DirectorRuntimeNode> nodes = new ArrayList<>();
        collect(commands.director().getRoot(), nodes);
        Set<String> canonicalPaths = new LinkedHashSet<>();
        int routed = 0;
        for (DirectorRuntimeNode node : nodes) {
            List<String> canonical = new DirectorRoute(node, List.of()).path();
            String key = String.join(" ", canonical);
            canonicalPaths.add(key);
            List<String> required = CommandService.requiredPermissions(canonical);
            assertEquals(EXPECTED_PERMISSIONS.get(key), required, key);
            assertSame(node, commands.route(sender, "brimstone", canonical.toArray(String[]::new)).node(), key);
            for (List<String> variant : variants(node)) {
                DirectorRoute route = commands.route(sender, "brimstone", variant.toArray(String[]::new));
                if (route.node() == node) {
                    assertEquals(required, CommandService.requiredPermissions(route.path()), String.join(" ", variant));
                    routed++;
                }
            }
        }
        assertEquals(EXPECTED_PERMISSIONS.keySet(), canonicalPaths);
        assertTrue(routed > nodes.size() * 2, "routed variants: " + routed);
    }

    @Test
    void misspelledAndAbbreviatedSubcommandsNeedTheRoutedCommandsPermission() {
        assertEquals(List.of("brimstone.give"), gate("giv", "pyro", "Steve", "2304"));
        assertEquals(List.of("brimstone.give"), gate("GIVE", "pyro", "Steve"));
        assertEquals(List.of("brimstone.clear"), gate("clea"));
        assertEquals(List.of("brimstone.clear"), gate("CLEAR"));
        assertEquals(List.of("brimstone.config"), gate("confi"));
        assertEquals(List.of("brimstone.debug"), gate("debu", "dum"));
        assertEquals(List.of("brimstone.command"), gate("debug", "versio"));
        assertEquals(List.of("brimstone.command"), gate("zzzzzzzz"));
        assertEquals(List.of("brimstone.command"), gate("help"));
        assertEquals(List.of("brimstone.command"), gate());
        assertEquals(List.of("brimstone.config"), CommandService.requiredPermissions(List.of("unregistered")));
    }

    @Test
    void misspelledAdministrationCommandsDoNothingForDefaultPlayers() {
        when(sender.hasPermission("brimstone.command")).thenReturn(true);
        when(sender.hasPermission("brimstone.language.self")).thenReturn(true);
        commands.onCommand(sender, null, "brimstone", new String[]{"giv", "pyro", "Steve", "2304"});
        commands.onCommand(sender, null, "brimstone", new String[]{"clea"});
        verify(plugin, times(2)).send(sender, "no-permission");
        verify(tnt, never()).give(any(Player.class), anyString(), anyInt());
        verify(tnt, never()).clear();
        assertEquals(List.of(), commands.onTabComplete(sender, null, "brimstone", new String[]{"giv", ""}));
        assertFalse(commands.onTabComplete(sender, null, "brimstone", new String[]{""}).contains("give"));
    }

    @Test
    void abbreviatedCommandsRunForSendersWithTheRoutedPermission() {
        when(sender.hasPermission("brimstone.clear")).thenReturn(true);
        when(tnt.clear()).thenReturn(4);
        commands.onCommand(sender, null, "brimstone", new String[]{"clea"});
        verify(tnt).clear();
        verify(plugin, never()).send(sender, "no-permission");
    }

    @Test
    void administrationHandlersRecheckPermissionWhenCalledDirectly() {
        BrimstoneCommands handlers = new BrimstoneCommands(plugin);
        handlers.give(sender, "pyro", "Steve", 2304);
        handlers.clear(sender);
        verify(plugin, times(2)).send(sender, "no-permission");
        verify(tnt, never()).give(any(Player.class), anyString(), anyInt());
        verify(tnt, never()).clear();
    }

    private List<String> gate(String... arguments) {
        return CommandService.requiredPermissions(commands.route(sender, "brimstone", arguments).path());
    }

    private static void collect(DirectorRuntimeNode node, List<DirectorRuntimeNode> nodes) {
        for (DirectorRuntimeNode child : node.getChildren()) {
            nodes.add(child);
            collect(child, nodes);
        }
    }

    private static List<List<String>> variants(DirectorRuntimeNode node) {
        List<DirectorRuntimeNode> chain = new ArrayList<>();
        for (DirectorRuntimeNode cursor = node; cursor.getParent() != null; cursor = cursor.getParent()) {
            chain.addFirst(cursor);
        }
        List<List<String>> variants = new ArrayList<>();
        for (int index = 0; index < chain.size(); index++) {
            for (String form : forms(chain.get(index))) {
                List<String> variant = new ArrayList<>(chain.size());
                for (int other = 0; other < chain.size(); other++) {
                    variant.add(other == index ? form : chain.get(other).getDescriptor().getName());
                }
                variants.add(variant);
            }
        }
        return variants;
    }

    private static Set<String> forms(DirectorRuntimeNode node) {
        Set<String> forms = new LinkedHashSet<>();
        for (String name : node.allNames()) {
            forms.add(name);
            forms.add(name.toUpperCase(Locale.ROOT));
            if (name.length() > 3) {
                forms.add(name.substring(0, name.length() - 1));
                forms.add(name.substring(0, 1) + name.charAt(2) + name.charAt(1) + name.substring(3));
            }
        }
        return forms;
    }
}
