/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.event;

/** Built-in client events. They carry only plain data so the API stays independent of Minecraft. */
public final class Events {
    private Events() {}

    /** End of every client tick (20 per second), in or out of a world. */
    public static final class ClientTick {
        public static final ClientTick INSTANCE = new ClientTick();
        private ClientTick() {}
    }

    /**
     * A physical mouse button changed state, posted before Minecraft handles it (also in menus).
     *
     * @param button    GLFW button: 0 = left, 1 = right, 2 = middle
     * @param pressed   true on press, false on release
     * @param timeNanos {@link System#nanoTime()} when it happened
     * @param inGame    true when no screen is open (the click reaches the game)
     */
    public record MouseButton(int button, boolean pressed, long timeNanos, boolean inGame) {
        public static final int LEFT = 0;
        public static final int RIGHT = 1;
    }

    /**
     * The local player attacked an entity (the attack was sent; it may still miss or be cancelled
     * by the server).
     *
     * @param entityId    network id of the target
     * @param distance    distance from the player's eyes to the nearest point of the target's hitbox, in blocks
     * @param critical    the attack met the vanilla critical-hit conditions
     * @param targetIsPlayer whether the target is another player
     */
    public record Attack(int entityId, double distance, boolean critical, boolean targetIsPlayer) {}

    /** The target of a recent {@link Attack} visibly took damage (its hurt animation started). */
    public record HitConfirmed(int entityId, double distance, boolean critical, boolean targetIsPlayer) {}

    /** The local player took damage. */
    public record PlayerHurt(float healthAfter) {}

    /** Joined a multiplayer server ({@code address} as typed, lower-case) or a singleplayer world ({@code null}). */
    public record ServerJoin(String address) {}

    /** Left the current world or server. */
    public static final class ServerLeave {
        public static final ServerLeave INSTANCE = new ServerLeave();
        private ServerLeave() {}
    }

    /** A chat or system message was shown, as plain text without formatting. */
    public record ChatMessage(String text) {}
}
