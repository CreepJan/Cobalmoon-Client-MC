/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.ui;

import dev.cobalmoon.api.setting.ColorSetting;
import dev.cobalmoon.core.render.GuiCanvas;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Colour picker popup: a hue/saturation wheel, a brightness bar, a transparency bar and the palette
 * swatches. Changes apply live; click outside or press Esc to close.
 */
public final class ColorPicker {
    private static final int WHEEL_R = 42;
    private static final int CELL = 2;
    private static final int BAR_W = 10;
    private static final int PAD = 8;
    public static final int WIDTH = PAD * 3 + WHEEL_R * 2 + BAR_W;
    public static final int HEIGHT = 20 + WHEEL_R * 2 + 52;

    private final String title;
    private final IntSupplier get;
    private final IntConsumer set;
    private float hue, sat, val;
    private int alpha;
    private int x, y;
    private Zone dragging;

    private enum Zone { WHEEL, VALUE, ALPHA }

    public ColorPicker(String title, IntSupplier get, IntConsumer set) {
        this.title = title;
        this.get = get;
        this.set = set;
        load(get.getAsInt());
    }

    private void load(int argb) {
        float[] hsv = toHsv(argb);
        hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        alpha = argb >>> 24;
    }

    public void position(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /** {x, y, width, height} on screen. */
    public int[] bounds() {
        return new int[]{x, y, WIDTH, HEIGHT};
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + WIDTH && my >= y && my < y + HEIGHT;
    }

    // ---- colour maths (HSV, h in [0,1)) ------------------------------------------------------

    public static int fromHsv(float h, float s, float v, int alpha) {
        h = ((h % 1f) + 1f) % 1f;
        float c = v * s;
        float hp = h * 6f;
        float xx = c * (1 - Math.abs(hp % 2 - 1));
        float r = 0, g = 0, b = 0;
        switch ((int) hp) {
            case 0 -> { r = c; g = xx; }
            case 1 -> { r = xx; g = c; }
            case 2 -> { g = c; b = xx; }
            case 3 -> { g = xx; b = c; }
            case 4 -> { r = xx; b = c; }
            default -> { r = c; b = xx; }
        }
        float m = v - c;
        int ri = Math.round((r + m) * 255), gi = Math.round((g + m) * 255), bi = Math.round((b + m) * 255);
        return (Math.max(0, Math.min(255, alpha)) << 24) | (ri << 16) | (gi << 8) | bi;
    }

    public static float[] toHsv(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == r) h = ((g - b) / d) / 6f;
        else if (max == g) h = ((b - r) / d + 2) / 6f;
        else h = ((r - g) / d + 4) / 6f;
        if (h < 0) h += 1;
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }

    private int current() {
        return fromHsv(hue, sat, val, alpha);
    }

    private void apply() {
        set.accept(current());
    }

    // ---- layout --------------------------------------------------------------------------------

    private int wheelCx() { return x + PAD + WHEEL_R; }
    private int wheelCy() { return y + 20 + WHEEL_R; }
    private int valueX() { return x + PAD * 2 + WHEEL_R * 2; }
    private int barTop() { return y + 20; }
    private int alphaY() { return y + 20 + WHEEL_R * 2 + 6; }
    private int swatchY() { return alphaY() + 14; }

    // ---- drawing -------------------------------------------------------------------------------

    public void render(GuiCanvas c, int mx, int my) {
        c.fill(x, y, x + WIDTH, y + HEIGHT, Theme.panel());
        c.outline(x, y, WIDTH, HEIGHT, Theme.accent());
        c.text(Widgets.ellipsize(c, title, WIDTH - 40), x + PAD, y + 6, Theme.text(), false);
        // preview swatch in the corner
        int cur = current();
        checker(c, x + WIDTH - 22, y + 4, 14, 12);
        c.fill(x + WIDTH - 22, y + 4, x + WIDTH - 8, y + 16, cur);

        // hue/saturation wheel at the current brightness
        int cx = wheelCx(), cy = wheelCy();
        for (int dy = -WHEEL_R; dy < WHEEL_R; dy += CELL) {
            for (int dx = -WHEEL_R; dx < WHEEL_R; dx += CELL) {
                double px = dx + CELL / 2.0, py = dy + CELL / 2.0;
                double dist = Math.sqrt(px * px + py * py);
                if (dist > WHEEL_R) continue;
                float h = (float) ((Math.atan2(py, px) / (2 * Math.PI) + 1) % 1);
                c.fill(cx + dx, cy + dy, cx + dx + CELL, cy + dy + CELL, fromHsv(h, (float) (dist / WHEEL_R), val, 255));
            }
        }
        double ang = hue * 2 * Math.PI;
        int mxk = cx + (int) Math.round(Math.cos(ang) * sat * WHEEL_R), myk = cy + (int) Math.round(Math.sin(ang) * sat * WHEEL_R);
        c.outline(mxk - 3, myk - 3, 7, 7, 0xFF000000);
        c.outline(mxk - 2, myk - 2, 5, 5, 0xFFFFFFFF);

        // brightness bar
        int vx = valueX(), top = barTop(), h = WHEEL_R * 2;
        for (int i = 0; i < h; i += CELL) {
            float v = 1f - (float) i / h;
            c.fill(vx, top + i, vx + BAR_W, top + i + CELL, fromHsv(hue, sat, v, 255));
        }
        int vy = top + Math.round((1 - val) * (h - 1));
        c.fill(vx - 2, vy - 1, vx + BAR_W + 2, vy + 2, 0xFFFFFFFF);
        c.outline(vx - 2, vy - 1, BAR_W + 4, 3, 0xFF000000);

        // transparency bar
        int ax = x + PAD, aw = WIDTH - PAD * 2, ay = alphaY();
        checker(c, ax, ay, aw, 8);
        for (int i = 0; i < aw; i += CELL) {
            int a = Math.round(255f * i / (aw - 1));
            c.fill(ax + i, ay, ax + i + CELL, ay + 8, fromHsv(hue, sat, val, a));
        }
        int axk = ax + Math.round(alpha / 255f * (aw - 1));
        c.fill(axk - 1, ay - 2, axk + 2, ay + 10, 0xFFFFFFFF);
        c.outline(axk - 2, ay - 2, 5, 12, 0xFF000000);

        // palette swatches
        int n = Theme.SWATCHES.length, sw = (aw - (n - 1)) / n, sy = swatchY();
        for (int i = 0; i < n; i++) {
            int sx = ax + i * (sw + 1);
            c.fill(sx, sy, sx + sw, sy + sw, Theme.SWATCHES[i]);
            if ((Theme.SWATCHES[i] & 0xFFFFFF) == (cur & 0xFFFFFF)) c.outline(sx - 1, sy - 1, sw + 2, sw + 2, Theme.accent());
        }
        c.text(ColorSetting.toHex(cur), ax, sy + sw + 5, Theme.muted(), false);
        String done = Tr.of("cobalmoon.picker.done");
        c.text(done, x + WIDTH - PAD - c.textWidth(done), sy + sw + 5, Theme.accent(), false);
    }

    private static void checker(GuiCanvas c, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, 0xFF808080);
        for (int yy = 0; yy < h; yy += 4) {
            for (int xx = ((yy / 4) % 2) * 4; xx < w; xx += 8) {
                c.fill(x + xx, y + yy, x + Math.min(w, xx + 4), y + Math.min(h, yy + 4), 0xFFC0C0C0);
            }
        }
    }

    // ---- input ---------------------------------------------------------------------------------

    /** Returns true if the picker should stay open after this click. */
    public boolean click(double mx, double my) {
        if (!contains(mx, my)) return false;
        int cx = wheelCx(), cy = wheelCy();
        if (Math.hypot(mx - cx, my - cy) <= WHEEL_R + 2) dragging = Zone.WHEEL;
        else if (mx >= valueX() - 2 && mx <= valueX() + BAR_W + 2 && my >= barTop() && my <= barTop() + WHEEL_R * 2) dragging = Zone.VALUE;
        else if (my >= alphaY() - 2 && my <= alphaY() + 10) dragging = Zone.ALPHA;
        else {
            int aw = WIDTH - PAD * 2, n = Theme.SWATCHES.length, sw = (aw - (n - 1)) / n, sy = swatchY();
            if (my >= sy && my < sy + sw) {
                int i = (int) ((mx - (x + PAD)) / (sw + 1));
                if (i >= 0 && i < n) {
                    int keepAlpha = alpha;
                    load(Theme.SWATCHES[i]);
                    alpha = keepAlpha == 0 ? 255 : keepAlpha;
                    apply();
                }
            } else if (my >= sy + sw + 2) {
                return false; // "Done"
            }
            return true;
        }
        drag(mx, my);
        return true;
    }

    public void drag(double mx, double my) {
        if (dragging == null) return;
        switch (dragging) {
            case WHEEL -> {
                double dx = mx - wheelCx(), dy = my - wheelCy();
                hue = (float) ((Math.atan2(dy, dx) / (2 * Math.PI) + 1) % 1);
                sat = (float) Math.min(1, Math.hypot(dx, dy) / WHEEL_R);
                if (val < 0.05f) val = 1f; // picking a hue on a black colour would otherwise do nothing
            }
            case VALUE -> val = (float) Math.max(0, Math.min(1, 1 - (my - barTop()) / (WHEEL_R * 2.0 - 1)));
            case ALPHA -> alpha = (int) Math.round(Math.max(0, Math.min(1, (mx - (x + PAD)) / (WIDTH - PAD * 2.0 - 1))) * 255);
        }
        apply();
    }

    public void release() {
        dragging = null;
    }
}
