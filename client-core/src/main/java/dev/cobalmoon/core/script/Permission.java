/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.script;

import java.util.Locale;
import java.util.Optional;

/**
 * What a script may do. A script asks for permissions in its manifest; nothing is available until the
 * player grants them. There is deliberately no permission for files, network, other mods or the account.
 */
public enum Permission {
    /** Draw its own movable text element on the HUD ({@code on_hud}). */
    HUD_DRAW("hud.draw"),
    /** Read chat lines ({@code on_chat}). */
    CHAT_READ("chat.read"),
    /** Send chat messages / commands (rate limited). */
    CHAT_SEND("chat.send"),
    /** Read your own position, health, FPS and CPS ({@code cobalmoon.player()}). */
    PLAYER_READ("player.read"),
    /** Combat events ({@code on_hit}). */
    COMBAT_READ("combat.read"),
    /** A small private key/value store (64 KB). */
    STORAGE("storage");

    public final String id;

    Permission(String id) {
        this.id = id;
    }

    public static Optional<Permission> of(String id) {
        for (Permission p : values()) if (p.id.equals(id)) return Optional.of(p);
        return Optional.empty();
    }

    public String translationKey() {
        return "cobalmoon.script.permission." + name().toLowerCase(Locale.ROOT);
    }
}
