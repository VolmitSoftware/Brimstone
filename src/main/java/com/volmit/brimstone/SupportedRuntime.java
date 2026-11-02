package com.volmit.brimstone;

import java.util.regex.Pattern;

final class SupportedRuntime {
    static final String MINECRAFT_RANGE = "26.1 through 26.3";
    static final int MINIMUM_JAVA = 25;
    private static final Pattern MINECRAFT_VERSION = Pattern.compile("26\\.[1-3](?:\\.\\d+)?(?:\\.build\\.\\d+-(?:stable|beta|alpha)|-R\\d+\\.\\d+(?:-SNAPSHOT)?)?");

    private SupportedRuntime() {
    }

    static boolean supports(String bukkitVersion, int javaFeature) {
        return javaFeature >= MINIMUM_JAVA && bukkitVersion != null && MINECRAFT_VERSION.matcher(bukkitVersion).matches();
    }

    static void requireSupported(String bukkitVersion, int javaFeature) {
        if (!supports(bukkitVersion, javaFeature)) {
            throw new IllegalStateException("Brimstone requires Minecraft " + MINECRAFT_RANGE + " on Java " + MINIMUM_JAVA
                    + " or newer; found Minecraft " + bukkitVersion + " on Java " + javaFeature + ".");
        }
    }
}
