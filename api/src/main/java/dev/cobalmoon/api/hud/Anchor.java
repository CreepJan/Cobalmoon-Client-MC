/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.hud;

/**
 * Screen point a HUD element is attached to. Offsets are measured from it, so elements keep
 * their place when the window or GUI scale changes.
 */
public enum Anchor {
    TOP_LEFT(0, 0), TOP_CENTER(0.5f, 0), TOP_RIGHT(1, 0),
    CENTER_LEFT(0, 0.5f), CENTER(0.5f, 0.5f), CENTER_RIGHT(1, 0.5f),
    BOTTOM_LEFT(0, 1), BOTTOM_CENTER(0.5f, 1), BOTTOM_RIGHT(1, 1);

    /** Horizontal and vertical position of the anchor, 0 = left/top, 1 = right/bottom. */
    public final float ax;
    public final float ay;

    Anchor(float ax, float ay) {
        this.ax = ax;
        this.ay = ay;
    }

    /** Anchor of the screen third that contains the point (x, y). */
    public static Anchor nearest(double x, double y, double screenW, double screenH) {
        int col = x < screenW / 3 ? 0 : x < screenW * 2 / 3 ? 1 : 2;
        int row = y < screenH / 3 ? 0 : y < screenH * 2 / 3 ? 1 : 2;
        return values()[row * 3 + col];
    }
}
