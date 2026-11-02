package com.volmit.brimstone.command;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgs;
import com.volmit.brimstone.Brimstone;
import org.bukkit.command.CommandSender;

@Director(name = "debug", description = "Diagnostic tools", descriptionKey = "command.description.debug")
public final class DebugCommands {
    private final Brimstone plugin;

    public DebugCommands(Brimstone plugin) {
        this.plugin = plugin;
    }

    @Director(name = "dump", description = "Save a diagnostic report", descriptionKey = "command.description.debug_dump")
    public void dump(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "upload", defaultValue = "false", descriptionKey = "command.parameter.upload") boolean upload) {
        if (!sender.hasPermission("brimstone.debug")) {
            plugin.send(sender, "no-permission");
            return;
        }
        plugin.debugDump().request(sender, upload);
    }

    @Director(name = "version", description = "Show the installed Brimstone version", descriptionKey = "command.description.version")
    public void version(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("brimstone.command")) {
            plugin.send(sender, "no-permission");
            return;
        }
        plugin.send(sender, "version", MessageArgs.builder()
                .untrusted("version", plugin.getDescription().getVersion()).build());
    }
}
