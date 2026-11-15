/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.input;

import dev.cobalmoon.api.event.EventBus;
import dev.cobalmoon.api.event.Events;

/** Global left/right click counters, fed by the mouse mixin. Shared by the CPS and keystrokes modules. */
public final class Clicks {
    public static final ClickTracker LEFT = new ClickTracker(64);
    public static final ClickTracker RIGHT = new ClickTracker(64);

    private Clicks() {}

    /** Counts presses that reach the game (not clicks inside menus). */
    public static void install(EventBus bus) {
        bus.subscribe(Events.MouseButton.class, e -> {
            if (!e.pressed() || !e.inGame()) return;
            if (e.button() == Events.MouseButton.LEFT) LEFT.record(e.timeNanos());
            else if (e.button() == Events.MouseButton.RIGHT) RIGHT.record(e.timeNanos());
        });
    }
}
