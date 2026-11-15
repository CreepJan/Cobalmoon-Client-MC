/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.render;

import dev.cobalmoon.api.hud.HudElement;
import dev.cobalmoon.api.hud.HudLayout;

import java.util.function.Consumer;

/** Draws HUD elements according to their layout. Shared by the in-game HUD and the HUD editor. */
public final class HudRenderer {
    private HudRenderer() {}

    /** Screen-space rectangle of an element (scaled size). */
    public record Bounds(int x, int y, int w, int h) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
        public int right() { return x + w; }
        public int bottom() { return y + h; }
    }

    public static Bounds bounds(HudElement e, GuiCanvas c, boolean preview) {
        HudLayout l = e.layout();
        int w = Math.max(1, Math.round(e.width(c, preview) * l.scale));
        int h = Math.max(1, Math.round(e.height(c, preview) * l.scale));
        return new Bounds(l.x(c.screenWidth(), w), l.y(c.screenHeight(), h), w, h);
    }

    /** Draws one element; errors are reported instead of crashing the frame. */
    public static void render(HudElement e, GuiCanvas c, boolean preview, Consumer<Throwable> onError) {
        HudLayout l = e.layout();
        try {
            Bounds b = bounds(e, c, preview);
            float prevAlpha = c.setAlpha(c.alpha() * l.opacity);
            c.push(b.x(), b.y(), l.scale);
            try {
                if (l.background && e.supportsBackground()) {
                    c.fill(0, 0, e.width(c, preview), e.height(c, preview), l.backgroundColor);
                }
                e.render(c, preview);
            } finally {
                c.pop();
                c.setAlpha(prevAlpha);
            }
        } catch (Throwable t) {
            onError.accept(t);
        }
    }
}
