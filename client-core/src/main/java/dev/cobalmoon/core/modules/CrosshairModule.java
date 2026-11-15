/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.modules;

import dev.cobalmoon.api.hud.HudCanvas;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.BooleanSetting;
import dev.cobalmoon.api.setting.ColorSetting;
import dev.cobalmoon.api.setting.EnumSetting;
import dev.cobalmoon.api.setting.NumberSetting;
import dev.cobalmoon.api.theme.DefaultTokens;

/** Replaces the vanilla crosshair with a fully configurable one (turquoise by default). */
public final class CrosshairModule extends Module {
    public enum Style { CROSS, DOT, CROSS_DOT, T, SQUARE }

    final EnumSetting<Style> style = setting(new EnumSetting<>("style", "cobalmoon.setting.crosshair.style", Style.CROSS,
            s -> "cobalmoon.crosshair.style." + s.name().toLowerCase(java.util.Locale.ROOT)));
    final NumberSetting length = setting(new NumberSetting("length", "cobalmoon.setting.crosshair.length", 4, 1, 20, 1));
    final NumberSetting gap = setting(new NumberSetting("gap", "cobalmoon.setting.crosshair.gap", 2, 0, 12, 1));
    final NumberSetting thickness = setting(new NumberSetting("thickness", "cobalmoon.setting.crosshair.thickness", 1, 1, 5, 1));
    final ColorSetting color = setting(new ColorSetting("color", "cobalmoon.setting.crosshair.color", DefaultTokens.ACCENT));
    final BooleanSetting outline = setting(new BooleanSetting("outline", "cobalmoon.setting.crosshair.outline", true));
    final ColorSetting outlineColor = setting(new ColorSetting("outline_color", "cobalmoon.setting.crosshair.outline_color", 0xCC000000 | (DefaultTokens.NIGHT & 0xFFFFFF)));

    public CrosshairModule() {
        super("crosshair", "cobalmoon.module.crosshair", "cobalmoon.module.crosshair.desc", Category.VISUAL);
    }

    /** Draws the crosshair centred at (cx, cy). */
    public void draw(HudCanvas c, int cx, int cy) {
        int t = thickness.intValue();
        int len = length.intValue();
        int g = gap.intValue();
        int half = t / 2;
        // Rectangles as {x1, y1, x2, y2}; the outline is drawn first, 1 px larger on each side.
        int[][] rects = switch (style.get()) {
            case DOT -> new int[][]{dot(cx, cy, t)};
            case SQUARE -> square(cx, cy, g, t);
            case CROSS -> arms(cx, cy, half, t, len, g, true);
            case T -> arms(cx, cy, half, t, len, g, false);
            case CROSS_DOT -> concat(arms(cx, cy, half, t, len, g, true), new int[][]{dot(cx, cy, t)});
        };
        if (outline.get()) {
            for (int[] r : rects) c.fill(r[0] - 1, r[1] - 1, r[2] + 1, r[3] + 1, outlineColor.get());
        }
        for (int[] r : rects) c.fill(r[0], r[1], r[2], r[3], color.get());
    }

    private static int[] dot(int cx, int cy, int t) {
        int s = Math.max(1, t);
        return new int[]{cx - s / 2, cy - s / 2, cx - s / 2 + s, cy - s / 2 + s};
    }

    private static int[][] arms(int cx, int cy, int half, int t, int len, int g, boolean top) {
        int x1 = cx - half, y1 = cy - half;
        int[] left = {cx - half - g - len, y1, cx - half - g, y1 + t};
        int[] right = {cx - half + t + g, y1, cx - half + t + g + len, y1 + t};
        int[] down = {x1, cy - half + t + g, x1 + t, cy - half + t + g + len};
        int[] up = {x1, cy - half - g - len, x1 + t, cy - half - g};
        return top ? new int[][]{left, right, up, down} : new int[][]{left, right, down};
    }

    private static int[][] square(int cx, int cy, int g, int t) {
        int r = Math.max(2, g + 2);
        return new int[][]{
                {cx - r, cy - r, cx + r + 1, cy - r + t},
                {cx - r, cy + r + 1 - t, cx + r + 1, cy + r + 1},
                {cx - r, cy - r + t, cx - r + t, cy + r + 1 - t},
                {cx + r + 1 - t, cy - r + t, cx + r + 1, cy + r + 1 - t}
        };
    }

    private static int[][] concat(int[][] a, int[][] b) {
        int[][] out = new int[a.length + b.length][];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
