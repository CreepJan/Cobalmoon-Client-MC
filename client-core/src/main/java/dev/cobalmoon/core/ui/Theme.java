/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.ui;

import dev.cobalmoon.api.setting.ColorSetting;
import dev.cobalmoon.api.theme.DefaultTokens;
import dev.cobalmoon.core.config.ConfigStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Colours of Cobalmoon's own interface (editor, panels, popups). Starts as the design tokens and can be
 * changed by the player in the theme editor; saved in {@code config/cobalmoon/theme.json}.
 * HUD elements keep their own per-element colours.
 */
public final class Theme {
    private static final String ENTRY = "theme";
    private static final Map<String, Integer> colors = new LinkedHashMap<>(DefaultTokens.COLORS);
    private static ConfigStore store;

    private Theme() {}

    /** Loads the saved theme; call once at startup. */
    public static void init(ConfigStore s) {
        store = s;
        Object saved = s.read(ENTRY).get("colors");
        if (saved instanceof Map<?, ?> m) apply(m);
    }

    /** Applies "key": "#AARRGGBB" pairs for known keys; unknown keys and bad values are ignored. */
    public static int apply(Map<?, ?> values) {
        int n = 0;
        for (var e : values.entrySet()) {
            String key = String.valueOf(e.getKey());
            Integer c = e.getValue() instanceof String str ? ColorSetting.parseHex(str) : null;
            if (c != null && DefaultTokens.COLORS.containsKey(key)) {
                colors.put(key, c | 0xFF000000); // theme colours are opaque
                n++;
            }
        }
        save();
        return n;
    }

    public static Map<String, Integer> colors() {
        return Collections.unmodifiableMap(colors);
    }

    public static int get(String key) {
        return colors.getOrDefault(key, DefaultTokens.COLORS.getOrDefault(key, 0xFFFF00FF));
    }

    public static void set(String key, int argb) {
        if (!DefaultTokens.COLORS.containsKey(key)) return;
        colors.put(key, argb | 0xFF000000);
        save();
    }

    public static void reset() {
        colors.clear();
        colors.putAll(DefaultTokens.COLORS);
        save();
    }

    public static Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        colors.forEach((k, v) -> m.put(k, ColorSetting.toHex(v)));
        return m;
    }

    private static void save() {
        if (store == null) return;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("colors", toJson());
        store.markDirty(ENTRY, () -> root);
    }

    // ---- semantic accessors used by the UI ----------------------------------------------------

    public static int panel() { return get("night-raised"); }
    public static int panelSunken() { return get("night-sunken"); }
    public static int hover() { return get("night-overlay"); }
    public static int border() { return withAlpha(get("lunar"), 0x66); }
    public static int text() { return get("stone"); }
    public static int muted() { return get("lunar"); }
    public static int accent() { return get("accent"); }
    public static int accentStrong() { return get("accent-strong"); }
    public static int onAccent() { return get("on-accent"); }
    public static int danger() { return get("danger"); }
    public static int warning() { return get("warning"); }
    public static int scrim() { return withAlpha(get("night-sunken"), 0x55); }

    public static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** Preset colours offered by colour pickers: the palette first, then common extras. */
    public static final int[] SWATCHES = {
            DefaultTokens.STONE, DefaultTokens.LUNAR, DefaultTokens.ACCENT, DefaultTokens.ACCENT_STRONG,
            DefaultTokens.NIGHT, DefaultTokens.NIGHT_RAISED, DefaultTokens.DANGER, DefaultTokens.WARNING,
            0xFFFFFFFF, 0xFF000000, 0xFFFF5555, 0xFF55FF55, 0xFF5599FF, 0xFFFFFF55, 0xFFFFAA00, 0xFFC77DFF
    };

    // ---- contrast (WCAG 2.x) ------------------------------------------------------------------

    public static double luminance(int argb) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double s = ((argb >> (16 - 8 * i)) & 0xFF) / 255.0;
            c[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    public static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** A text/background pair below its WCAG minimum. */
    public record Warning(String fg, String bg, double ratio, double minimum) {}

    /** Same pairs the design-token CI checks, evaluated on the current theme. */
    public static List<Warning> contrastWarnings() {
        String[][] pairs = {
                {"stone", "night-raised", "4.5"}, {"stone", "night-sunken", "4.5"}, {"lunar", "night-raised", "4.5"},
                {"accent", "night-raised", "3"}, {"on-accent", "accent", "4.5"}, {"on-accent", "accent-strong", "4.5"},
        };
        List<Warning> out = new ArrayList<>();
        for (String[] p : pairs) {
            double r = contrast(get(p[0]), get(p[1])), min = Double.parseDouble(p[2]);
            if (r < min) out.add(new Warning(p[0], p[1], r, min));
        }
        return out;
    }

    /** Keys in the order the theme editor lists them. */
    public static final List<String> EDITABLE = List.of(
            "night", "night-raised", "night-sunken", "night-overlay", "stone", "lunar",
            "accent", "accent-strong", "on-accent", "danger", "warning");
}
