/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.render;

import dev.cobalmoon.api.hud.HudCanvas;

/**
 * {@link HudCanvas} plus the transforms the core needs. Each Minecraft version provides one
 * implementation wrapping its own GUI drawing class.
 */
public interface GuiCanvas extends HudCanvas {
    /** Moves the origin to (x, y) and scales; must be balanced with {@link #pop()}. */
    void push(float x, float y, float scale);

    void pop();

    /** Multiplies the alpha of everything drawn after this call (0..1). Returns the previous value. */
    float setAlpha(float alpha);

    float alpha();

    /** Clips drawing to the rectangle, in screen coordinates. Must be balanced with {@link #endClip()}. */
    void clip(int x1, int y1, int x2, int y2);

    void endClip();

    /**
     * Starts a new drawing layer: everything drawn afterwards appears above everything drawn before.
     * Needed because Minecraft batches all text after all fills within one layer.
     */
    void nextLayer();

    /** Applies the current alpha multiplier to an ARGB colour. */
    static int mulAlpha(int argb, float alpha) {
        if (alpha >= 1f) return argb;
        int a = Math.round((argb >>> 24) * Math.max(0f, alpha));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
