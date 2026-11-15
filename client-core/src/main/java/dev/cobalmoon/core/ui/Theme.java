/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.ui;

import dev.cobalmoon.api.theme.DefaultTokens;

/**
 * Colours used by the in-game UI, derived from the design tokens. Phase 6 makes these user-editable;
 * until then they are the Cobalmoon defaults.
 */
public final class Theme {
    private Theme() {}

    public static int panel() { return DefaultTokens.NIGHT_RAISED; }
    public static int panelSunken() { return DefaultTokens.NIGHT_SUNKEN; }
    public static int hover() { return DefaultTokens.NIGHT_OVERLAY; }
    public static int border() { return withAlpha(DefaultTokens.LUNAR, 0x66); }
    public static int text() { return DefaultTokens.STONE; }
    public static int muted() { return DefaultTokens.LUNAR; }
    public static int accent() { return DefaultTokens.ACCENT; }
    public static int accentStrong() { return DefaultTokens.ACCENT_STRONG; }
    public static int onAccent() { return DefaultTokens.ON_ACCENT; }
    public static int danger() { return DefaultTokens.DANGER; }
    public static int scrim() { return withAlpha(DefaultTokens.NIGHT_SUNKEN, 0x55); }

    public static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** Preset colours offered by colour pickers: the palette first, then common extras. */
    public static final int[] SWATCHES = {
            DefaultTokens.STONE, DefaultTokens.LUNAR, DefaultTokens.ACCENT, DefaultTokens.ACCENT_STRONG,
            DefaultTokens.NIGHT, DefaultTokens.NIGHT_RAISED, DefaultTokens.DANGER, DefaultTokens.WARNING,
            0xFFFFFFFF, 0xFF000000, 0xFFFF5555, 0xFF55FF55, 0xFF5599FF, 0xFFFFFF55, 0xFFFFAA00, 0xFFC77DFF
    };
}
