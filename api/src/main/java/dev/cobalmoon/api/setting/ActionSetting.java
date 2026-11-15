/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.setting;

import java.util.Objects;

/** A button in the module's settings. Not saved. */
public final class ActionSetting extends Setting<Boolean> {
    private final Runnable action;

    public ActionSetting(String key, String name, Runnable action) {
        super(key, name, false);
        this.action = Objects.requireNonNull(action);
    }

    public void run() { action.run(); }

    /** Never persisted. */
    @Override public Object toJson() { return null; }

    @Override public void fromJson(Object json) {}
}
