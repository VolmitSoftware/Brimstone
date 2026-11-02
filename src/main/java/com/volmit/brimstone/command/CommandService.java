package com.volmit.brimstone.command;

import art.arcane.volmlib.util.director.DirectorEngineOptions;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRoute;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import com.volmit.brimstone.Brimstone;
import com.volmit.brimstone.presentation.BrimstoneTheme;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class CommandService implements CommandExecutor, TabCompleter {
    private static final List<String> COMMANDS = List.of("list", "give", "clear", "config", "language", "status", "debug");
    private static final List<String> UNMAPPED_PERMISSION = List.of("brimstone.config");
    private static final Map<String, List<String>> PERMISSIONS = Map.ofEntries(
            Map.entry("", List.of("brimstone.command")),
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
    private final Brimstone plugin;
    private final DirectorRuntimeEngine director;

    public CommandService(Brimstone plugin) {
        this.plugin = plugin;
        DirectorContextRegistry contexts = new DirectorContextRegistry();
        contexts.register(CommandSender.class, (invocation, values) ->
                invocation.getSender() instanceof Sender sender ? sender.delegate() : null);
        director = DirectorEngineFactory.create(new BrimstoneCommands(plugin), DirectorEngineOptions.builder()
                .contexts(contexts).textResolver(plugin.language().directorResolver()).build());
    }

    public void register() {
        PluginCommand command = Objects.requireNonNull(plugin.getCommand("brimstone"), "Missing brimstone command");
        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        UUID audience = sender instanceof Player player ? player.getUniqueId() : null;
        return LanguageAudience.call(audience, () -> execute(sender, label, arguments));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
        if (arguments.length <= 1) {
            String prefix = arguments.length == 0 ? "" : arguments[0].toLowerCase(Locale.ROOT);
            return COMMANDS.stream()
                    .filter(name -> holdsAny(sender, requiredPermissions(List.of(name))) && name.startsWith(prefix)).toList();
        }
        DirectorRoute route = route(sender, alias, Arrays.copyOf(arguments, arguments.length - 1));
        if (!holdsAny(sender, requiredPermissions(route.path()))) {
            return List.of();
        }
        String last = arguments[arguments.length - 1];
        String prefix = last.toLowerCase(Locale.ROOT);
        int position = route.remainingArgs().size();
        return switch (String.join(" ", route.path())) {
            case "debug" -> position != 0 ? List.of() : List.of("dump", "version").stream()
                    .filter(option -> holdsAny(sender, requiredPermissions(List.of("debug", option))) && option.startsWith(prefix))
                    .toList();
            case "debug dump" -> position != 0 ? List.of() : List.of("true", "false").stream()
                    .filter(option -> option.startsWith(prefix)).toList();
            case "language" -> plugin.languageSwitcher().complete(sender, languageArguments(route, last));
            case "give" -> giveOptions(position).stream()
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
            default -> List.of();
        };
    }

    static List<String> requiredPermissions(List<String> path) {
        return PERMISSIONS.getOrDefault(String.join(" ", path), UNMAPPED_PERMISSION);
    }

    DirectorRuntimeEngine director() {
        return director;
    }

    DirectorRoute route(CommandSender sender, String label, String[] arguments) {
        if (arguments.length > 0 && helpToken(arguments[0])) {
            return new DirectorRoute(null, Arrays.asList(arguments));
        }
        return director.route(new DirectorInvocation(new Sender(sender), label, Arrays.asList(arguments)));
    }

    private boolean execute(CommandSender sender, String label, String[] arguments) {
        DirectorRoute route = route(sender, label, arguments);
        if (!holdsAny(sender, requiredPermissions(route.path()))) {
            plugin.send(sender, "no-permission");
            return true;
        }
        try {
            if (route.path().equals(List.of("language"))) {
                return plugin.languageSwitcher().command(sender, route.remainingArgs().toArray(String[]::new));
            }
            List<String> args = normalize(arguments);
            Optional<DirectorMiniMenu.DirectorHelpPage> help = DirectorMiniMenu.resolveHelp(director, args);
            if (help.isPresent()) {
                showHelp(sender, help.get());
            } else if (!director.execute(new DirectorInvocation(new Sender(sender), label, args)).isHandled()) {
                DirectorMiniMenu.resolveHelp(director, List.of()).ifPresent(page -> showHelp(sender, page));
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Brimstone command failed", exception);
            plugin.send(sender, "command.failed");
        }
        return true;
    }

    private List<String> giveOptions(int position) {
        return switch (position) {
            case 0 -> List.copyOf(plugin.configuration().runtime().definitions().keySet());
            case 1 -> plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
            case 2 -> List.of("1", "16", "64");
            default -> List.of();
        };
    }

    private static String[] languageArguments(DirectorRoute route, String last) {
        List<String> remaining = new ArrayList<>(route.remainingArgs());
        remaining.add(last);
        return remaining.toArray(String[]::new);
    }

    private static boolean holdsAny(CommandSender sender, List<String> permissions) {
        for (String permission : permissions) {
            if (sender.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    private static boolean helpToken(String token) {
        return token.isBlank() || token.equals("?") || token.equalsIgnoreCase("help");
    }

    private void showHelp(CommandSender sender, DirectorMiniMenu.DirectorHelpPage page) {
        List<DirectorRuntimeNode> entries = page.entries().stream()
                .filter(node -> !node.getDescriptor().isHidden())
                .filter(node -> holdsAny(sender, requiredPermissions(new DirectorRoute(node, List.of()).path()))).toList();
        DirectorMiniMenu.deliver(sender, page.withEntries(entries), BrimstoneTheme.theme(), plugin.language().directorResolver());
    }

    static List<String> normalize(String[] arguments) {
        List<String> normalized = new ArrayList<>(Arrays.asList(arguments));
        if (arguments.length == 4 && arguments[0].equalsIgnoreCase("give") && !arguments[3].contains("=")) {
            normalized.set(3, "amount=" + arguments[3]);
        }
        if (arguments.length == 3 && arguments[0].equalsIgnoreCase("debug")
                && arguments[1].equalsIgnoreCase("dump") && !arguments[2].contains("=")) {
            normalized.set(2, "upload=" + arguments[2]);
        }
        return normalized;
    }

    private record Sender(CommandSender delegate) implements DirectorSender {
        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public boolean isPlayer() {
            return delegate instanceof Player;
        }

        @Override
        public void sendMessage(String message) {
            ComponentMessenger.send(delegate, ComponentText.literal(message).colorIfAbsent("#fff1d0"));
        }
    }
}
