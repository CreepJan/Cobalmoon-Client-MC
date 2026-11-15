/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.hud;

/**
 * Minimal drawing surface handed to HUD elements. Coordinates are local to the element
 * (0,0 = its top-left corner, already scaled). Colours are ARGB; the element's opacity is
 * applied automatically.
 */
public interface HudCanvas {
    int textWidth(String text);

    /** Height of one line of text, without spacing. */
    int fontHeight();

    void text(String text, int x, int y, int argb, boolean shadow);

    /** Filled rectangle from (x1, y1) inclusive to (x2, y2) exclusive. */
    void fill(int x1, int y1, int x2, int y2, int argb);

    /** 1-pixel outline of the rectangle (x, y, w, h). */
    default void outline(int x, int y, int w, int h, int argb) {
        fill(x, y, x + w, y + 1, argb);
        fill(x, y + h - 1, x + w, y + h, argb);
        fill(x, y + 1, x + 1, y + h - 1, argb);
        fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
    }

    default void centeredText(String text, int centerX, int y, int argb, boolean shadow) {
        text(text, centerX - textWidth(text) / 2, y, argb, shadow);
    }

    /** GUI-scaled screen size. */
    int screenWidth();

    int screenHeight();
}
