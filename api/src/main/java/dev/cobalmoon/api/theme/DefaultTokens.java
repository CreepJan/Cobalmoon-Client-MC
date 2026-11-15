/*
 * Generated from design-tokens/tokens.json by design-tokens/scripts/build.mjs. Do not edit by hand.
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.theme;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Default Cobalmoon palette as opaque ARGB ints. Themes override these by key. */
public final class DefaultTokens {
    private DefaultTokens() {}

    /** Azul noche — main backgrounds, dark panels */
    public static final int NIGHT = 0xFF10182C;

    /** Azul lunar — secondary elements, borders, muted text, inactive states */
    public static final int LUNAR = 0xFF7486B8;

    /** Turquesa — primary accent: buttons, selection, active toggles, default crosshair */
    public static final int ACCENT = 0xFF52E0D0;

    /** Blanco piedra — primary text, icons, hairlines */
    public static final int STONE = 0xFFE9EDF2;

    /** Derived: deeper background (window chrome, sidebars) */
    public static final int NIGHT_SUNKEN = 0xFF0B1120;

    /** Derived: raised panels and cards */
    public static final int NIGHT_RAISED = 0xFF141D35;

    /** Derived: hover / popovers */
    public static final int NIGHT_OVERLAY = 0xFF222E4F;

    /** Derived: accent hover/pressed */
    public static final int ACCENT_STRONG = 0xFF7FF0E3;

    /** Text and icons drawn on top of the accent (stone on accent fails WCAG) */
    public static final int ON_ACCENT = 0xFF10182C;

    /** Errors and destructive actions */
    public static final int DANGER = 0xFFFF7A88;

    /** Warnings (e.g. Safe Server Mode notices) */
    public static final int WARNING = 0xFFFFC86B;

    public static final int MOTION_FAST_MS = 120;
    public static final int MOTION_NORMAL_MS = 220;
    public static final int MOTION_SLOW_MS = 380;

    /** All colour tokens by their token name (e.g. "accent"), in declaration order. */
    public static final Map<String, Integer> COLORS;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("night", NIGHT);
        m.put("lunar", LUNAR);
        m.put("accent", ACCENT);
        m.put("stone", STONE);
        m.put("night-sunken", NIGHT_SUNKEN);
        m.put("night-raised", NIGHT_RAISED);
        m.put("night-overlay", NIGHT_OVERLAY);
        m.put("accent-strong", ACCENT_STRONG);
        m.put("on-accent", ON_ACCENT);
        m.put("danger", DANGER);
        m.put("warning", WARNING);
        COLORS = Collections.unmodifiableMap(m);
    }
}
