package com.volmit.brimstone.localization;

import art.arcane.volmlib.util.config.BukkitConfigMessages;
import art.arcane.volmlib.util.diagnostics.BukkitDebugMessages;
import art.arcane.volmlib.util.director.DirectorMessages;
import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.TextKey;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BrimstoneMessages {
    public static final String PREFIX = "runtime.prefix";
    public static final String CHAT_PREFIX = "{prefix}&r &7› &7";

    private BrimstoneMessages() {
    }

    public static Map<String, String> english() {
        Map<String, String> text = new LinkedHashMap<>();
        text.put(PREFIX, "<bold><gradient:#ff6b6b:#fff1d0>Brimstone</gradient></bold>");
        chat().forEach((id, body) -> text.put(id, CHAT_PREFIX + body));
        text.put("command.description.give", "Give a TNT launcher to a player.");
        text.put("command.description.list", "List configured TNT types.");
        text.put("command.description.clear", "Remove owned TNT and secondary effects.");
        text.put("command.description.config", "Edit the configuration.");
        text.put("command.description.language", "Select or edit a language.");
        text.put("command.description.status", "Show version and runtime counts.");
        text.put("command.description.debug", "Diagnostic tools");
        text.put("command.description.debug_dump", "Save a diagnostic report");
        text.put("command.description.version", "Show the installed Brimstone version");
        text.put("command.parameter.id", "Configured TNT identifier");
        text.put("command.parameter.player", "Online player name");
        text.put("command.parameter.amount", "Number of launchers");
        text.put("command.parameter.upload", "Upload the diagnostic report");
        return text;
    }

    private static Map<String, String> chat() {
        Map<String, String> chat = new LinkedHashMap<>();
        chat.put("runtime.hot_reload.success", "&aApplied&7 configuration and language file changes.&r");
        chat.put("runtime.hot_reload.failed", "&cRejected file changes; the last known good settings remain active.&r");
        chat.put("no-permission", "&cYou do not have permission.&r");
        chat.put("command.failed", "&cThe command failed. Check the server log.&r");
        chat.put("give.done", "Gave {amount} {id} TNT to {player}.");
        chat.put("give.invalid", "&cChoose a configured TNT ID and an amount from 1 to 2304.&r");
        chat.put("player.missing", "&cThat player is not online.&r");
        chat.put("list", "TNT types: {types}");
        chat.put("status", "v{version}: {types} types, {active} active TNT, {secondary} secondary effects.");
        chat.put("version", "Version {version}");
        chat.put("clear.done", "Scheduled cleanup of {count} owned effects.");
        chat.put("launch.no-fuel", "&cYou do not have enough fuel for this launcher.&r");
        chat.put("launch.cooldown", "&cWait before launching again.&r");
        chat.put("launch.no-permission", "&cYou do not have permission to use this launcher.&r");
        chat.put("launch.limit-player", "&cYou have reached your active TNT limit.&r");
        chat.put("launch.limit-world", "&cThis world has reached its active TNT limit.&r");
        chat.put("launch.limit-server", "&cThe server has reached its active TNT limit.&r");
        chat.put("access.world-denied", "&cYou cannot launch TNT in this world.&r");
        chat.put("access.protection-denied", "&cYou cannot launch TNT in this protected area.&r");
        chat.put("craft.no-permission", "&cYou do not have permission to craft this TNT.&r");
        chat.put("craft.world-denied", "&cYou cannot craft TNT in this world.&r");
        return chat;
    }

    public static MessageCatalog catalog() {
        MessageCatalog.Builder builder = MessageCatalog.builder("en_US");
        english().forEach((id, value) -> builder.add(value.startsWith(CHAT_PREFIX)
                ? TextKey.ofOptional(id, value, "prefix") : TextKey.of(id, value)));
        builder.addAll(DirectorMessages.keys());
        builder.addAll(BukkitLanguageMessages.keys());
        builder.addAll(BukkitDebugMessages.keys());
        builder.addAll(BukkitConfigMessages.keys());
        builder.add(BukkitConfigMessages.ADJUST);
        return builder.build();
    }
}
