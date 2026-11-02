package com.volmit.brimstone.command;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.brimstone.Brimstone;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

@Director(name = "brimstone", description = "TNT launchers and administration")
public final class BrimstoneCommands {
    private final Brimstone plugin;
    private DebugCommands debug;

    public BrimstoneCommands(Brimstone plugin) {
        this.plugin = plugin;
        debug = new DebugCommands(plugin);
    }

    @Director(name = "status", description = "Show version and active TNT", descriptionKey = "command.description.status")
    public void status(@Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.send(sender, "status", MessageArgs.builder()
                .untrusted("version", plugin.getDescription().getVersion())
                .untrusted("types", plugin.configuration().runtime().definitions().size())
                .untrusted("active", plugin.tnt().activeCount())
                .untrusted("secondary", plugin.tnt().secondaryCount()).build());
    }

    @Director(name = "list", description = "List configured TNT IDs", descriptionKey = "command.description.list")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.send(sender, "list", MessageArgs.builder()
                .untrusted("types", String.join(", ", plugin.configuration().runtime().definitions().keySet())).build());
    }

    @Director(name = "give", description = "Give a player custom TNT", descriptionKey = "command.description.give")
    public void give(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "id", descriptionKey = "command.parameter.id") String id,
                     @Param(name = "player", descriptionKey = "command.parameter.player") String playerName,
                     @Param(name = "amount", defaultValue = "1", descriptionKey = "command.parameter.amount") int amount) {
        if (!sender.hasPermission("brimstone.give")) {
            plugin.send(sender, "no-permission");
            return;
        }
        String canonicalId = id.toLowerCase(Locale.ROOT);
        if (amount < 1 || amount > 2304 || !plugin.configuration().runtime().definitions().containsKey(canonicalId)) {
            plugin.send(sender, "give.invalid");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(playerName);
        if (target == null) {
            plugin.send(sender, "player.missing");
            return;
        }
        boolean scheduled = FoliaScheduler.runEntity(plugin, target, () -> {
            if (!target.isOnline()) {
                plugin.send(sender, "player.missing");
                return;
            }
            if (plugin.tnt().give(target, canonicalId, amount)) {
                plugin.send(sender, "give.done", MessageArgs.builder().untrusted("amount", amount)
                        .untrusted("id", canonicalId).untrusted("player", target.getName()).build());
            } else {
                plugin.send(sender, "give.invalid");
            }
        }, 0L, () -> plugin.send(sender, "player.missing"));
        if (!scheduled) {
            plugin.send(sender, "player.missing");
        }
    }

    @Director(name = "clear", description = "Remove owned TNT and secondary effects", descriptionKey = "command.description.clear")
    public void clear(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("brimstone.clear")) {
            plugin.send(sender, "no-permission");
            return;
        }
        plugin.send(sender, "clear.done", MessageArgs.builder().untrusted("count", plugin.tnt().clear()).build());
    }

    @Director(name = "config", description = "Edit TNT settings in game", descriptionKey = "command.description.config")
    public void config(@Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.configEditor().open(sender);
    }

    @Director(name = "language", description = "Choose or edit a language", descriptionKey = "command.description.language")
    public void language(@Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.languageSwitcher().open(sender);
    }

    @Director(name = "version", hidden = true, description = "Show the installed Brimstone version", descriptionKey = "command.description.version")
    public void version(@Param(name = "sender", contextual = true) CommandSender sender) {
        debug.version(sender);
    }
}
