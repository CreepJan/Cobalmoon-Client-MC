/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.render;

/** Callbacks the version-specific platform layer invokes while drawing the HUD. */
public final class HudLayer {
    private HudLayer() {}

    @FunctionalInterface
    public interface Draw {
        void draw(GuiCanvas canvas);
    }

    /** Returns true if it handled the crosshair (vanilla is then skipped), false to draw vanilla. */
    @FunctionalInterface
    public interface Crosshair {
        boolean draw(GuiCanvas canvas);
    }
}
