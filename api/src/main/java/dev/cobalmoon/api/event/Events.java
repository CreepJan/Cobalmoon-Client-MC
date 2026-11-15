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
}
