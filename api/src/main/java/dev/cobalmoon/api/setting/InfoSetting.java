/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.setting;

import java.util.Objects;
import java.util.function.Supplier;

/** Read-only text shown in the module's settings (status, help). Not saved. */
public final class InfoSetting extends Setting<String> {
    private final Supplier<String> text;

    public InfoSetting(String key, Supplier<String> text) {
        super(key, key, "");
        this.text = Objects.requireNonNull(text);
    }

    public String text() {
        String t = text.get();
        return t == null ? "" : t;
    }

    @Override public Object toJson() { return null; }

    @Override public void fromJson(Object json) {}
}
