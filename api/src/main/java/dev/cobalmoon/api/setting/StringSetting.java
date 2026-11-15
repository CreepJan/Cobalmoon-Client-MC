/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.setting;

/** Free text (shown as a text field). Longer values are cut to {@link #maxLength()}. */
public final class StringSetting extends Setting<String> {
    private final int maxLength;

    public StringSetting(String key, String name, String defaultValue, int maxLength) {
        super(key, name, defaultValue);
        if (maxLength < 1) throw new IllegalArgumentException("maxLength must be positive");
        this.maxLength = maxLength;
    }

    public int maxLength() { return maxLength; }

    @Override
    protected String sanitize(String value) {
        // No control characters (they could break chat messages or config files).
        String clean = value.replaceAll("\\p{Cntrl}", "");
        return clean.length() > maxLength ? clean.substring(0, maxLength) : clean;
    }

    @Override public Object toJson() { return get(); }

    @Override
    public void fromJson(Object json) {
        set(json instanceof String s ? s : defaultValue());
    }
}
