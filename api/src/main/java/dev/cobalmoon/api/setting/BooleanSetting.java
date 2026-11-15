/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.setting;

public final class BooleanSetting extends Setting<Boolean> {
    public BooleanSetting(String key, String name, boolean defaultValue) {
        super(key, name, defaultValue);
    }

    public void toggle() { set(!get()); }

    @Override public Object toJson() { return get(); }

    @Override
    public void fromJson(Object json) {
        set(json instanceof Boolean b ? b : defaultValue());
    }
}
