/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.setting;

import java.util.Locale;

/** An ARGB colour, stored as "#AARRGGBB". */
public final class ColorSetting extends Setting<Integer> {
    public ColorSetting(String key, String name, int defaultArgb) {
        super(key, name, defaultArgb);
    }

    public static String toHex(int argb) {
        return String.format(Locale.ROOT, "#%08X", argb);
    }

    /** Parses "#RRGGBB" (opaque) or "#AARRGGBB". Returns null when invalid. */
    public static Integer parseHex(String s) {
        if (s == null) return null;
        String h = s.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (!h.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) return null;
        long v = Long.parseLong(h, 16);
        return h.length() == 6 ? (int) (0xFF000000L | v) : (int) v;
    }

    public int alpha() { return get() >>> 24; }

    /** Same colour with a new alpha (0-255). */
    public void setAlpha(int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        set((a << 24) | (get() & 0x00FFFFFF));
    }

    @Override public Object toJson() { return toHex(get()); }

    @Override
    public void fromJson(Object json) {
        Integer parsed = json instanceof String s ? parseHex(s) : null;
        set(parsed != null ? parsed : defaultValue());
    }
}
