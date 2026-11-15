/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core;

/** Product naming. "Minecraft" is never used as part of our brand, only as "for Minecraft". */
public final class Branding {
    public static final String NAME = "Cobalmoon Client";

    private Branding() {}

    /**
     * Window title: keeps whatever the game (or another mod) produced and prefixes our name,
     * so information such as "Multiplayer (3rd-party Server)" is preserved.
     */
    public static String windowTitle(String original, String clientVersion) {
        String prefix = NAME + " " + clientVersion;
        if (original == null || original.isBlank()) {
            return prefix;
        }
        if (original.startsWith(NAME)) {
            return original; // already branded (e.g. title recomputed)
        }
        return prefix + " · " + original;
    }

    /** Line drawn on the title screen. */
    public static String titleScreenLine(String clientVersion, String minecraftVersion) {
        return NAME + " " + clientVersion + " para Minecraft " + minecraftVersion;
    }
}
