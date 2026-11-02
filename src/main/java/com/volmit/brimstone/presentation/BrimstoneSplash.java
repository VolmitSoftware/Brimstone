package com.volmit.brimstone.presentation;

import art.arcane.volmlib.util.plugin.ComponentLog;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.plugin.SplashScreenSupport;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

public final class BrimstoneSplash {
    private static final List<String> EMBLEM = List.of(
            "       *       ",
            "      /        ",
            "  +---------+  ",
            "  | BRIM /  |  ",
            "  |     /   |  ",
            "  +---------+  ");

    private BrimstoneSplash() {
    }

    public static void print(JavaPlugin plugin) {
        try {
            List<String> details = List.of(
                    "Brimstone | Configurable TNT",
                    "Version: " + plugin.getDescription().getVersion(),
                    "By: VolmitSoftware (Arcane Arts) | VolmitSoftware.com",
                    "Server: " + SplashScreenSupport.serverVersionWithoutMcSuffix(plugin.getServer().getVersion()),
                    "Minecraft: 26.1-26.3 | Java: " + Runtime.version().feature() + " (minimum 25)",
                    "Started: " + SplashScreenSupport.startupDate());
            ComponentText banner = ComponentText.literal("\n");
            for (int row = 0; row < EMBLEM.size(); row++) {
                banner = banner.append(ComponentText.literal(EMBLEM.get(row)).colorIfAbsent(BrimstoneTheme.EMBER))
                        .append(ComponentText.literal("  " + details.get(row) + "\n").colorIfAbsent(BrimstoneTheme.IVORY));
            }
            ComponentLog.log(plugin, plugin.getLogger(), "", Level.INFO, banner, null);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not render the Brimstone startup banner", exception);
        }
    }
}
