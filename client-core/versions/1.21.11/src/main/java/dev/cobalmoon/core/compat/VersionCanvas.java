/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.compat;

import dev.cobalmoon.core.render.GuiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** {@link GuiCanvas} over 1.21.11's {@link GuiGraphics}. */
final class VersionCanvas implements GuiCanvas {
    private final GuiGraphics g;
    private final Font font = Minecraft.getInstance().font;
    private float alpha = 1f;

    VersionCanvas(GuiGraphics g) {
        this.g = g;
    }

    @Override public int textWidth(String text) { return font.width(text); }
    @Override public int fontHeight() { return font.lineHeight - 1; }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        int c = GuiCanvas.mulAlpha(argb, alpha);
        if ((c >>> 24) > 3) g.drawString(font, text, x, y, c, shadow);
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        int c = GuiCanvas.mulAlpha(argb, alpha);
        if ((c >>> 24) != 0) g.fill(x1, y1, x2, y2, c);
    }

    @Override
    public void push(float x, float y, float scale) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
    }

    @Override public void pop() { g.pose().popMatrix(); }

    @Override
    public float setAlpha(float a) {
        float prev = alpha;
        alpha = a;
        return prev;
    }

    @Override public float alpha() { return alpha; }
    @Override public void clip(int x1, int y1, int x2, int y2) { g.enableScissor(x1, y1, x2, y2); }
    @Override public void endClip() { g.disableScissor(); }
    @Override public void nextLayer() { g.nextStratum(); }
    @Override public int screenWidth() { return g.guiWidth(); }
    @Override public int screenHeight() { return g.guiHeight(); }
}
