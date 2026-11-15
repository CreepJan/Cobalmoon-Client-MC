/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.hud;

import dev.cobalmoon.api.setting.ColorSetting;
import dev.cobalmoon.api.theme.DefaultTokens;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where and how a HUD element is drawn: anchor + offset, scale, opacity and style.
 * Everything here is editable in the HUD editor and saved per element.
 */
public final class HudLayout {
    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 3.0f;

    public Anchor anchor;
    /** Offset from the anchor in GUI pixels; positive values move towards the screen centre. */
    public int offsetX;
    public int offsetY;
    public float scale = 1f;
    /** 0..1, multiplies every colour of the element. */
    public float opacity = 1f;
    public boolean background = true;
    public int backgroundColor = (0x99 << 24) | (DefaultTokens.NIGHT & 0xFFFFFF);
    public int textColor = DefaultTokens.STONE;
    public int accentColor = DefaultTokens.ACCENT;
    public boolean shadow = true;

    public HudLayout(Anchor anchor, int offsetX, int offsetY) {
        this.anchor = anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    public HudLayout copy() {
        HudLayout c = new HudLayout(anchor, offsetX, offsetY);
        c.copyFrom(this);
        return c;
    }

    public void copyFrom(HudLayout o) {
        anchor = o.anchor;
        offsetX = o.offsetX;
        offsetY = o.offsetY;
        scale = o.scale;
        opacity = o.opacity;
        background = o.background;
        backgroundColor = o.backgroundColor;
        textColor = o.textColor;
        accentColor = o.accentColor;
        shadow = o.shadow;
    }

    public void setScale(float s) {
        scale = Math.round(Math.max(MIN_SCALE, Math.min(MAX_SCALE, s)) * 20f) / 20f;
    }

    public void setOpacity(float o) {
        opacity = Math.round(Math.max(0.1f, Math.min(1f, o)) * 20f) / 20f;
    }

    /**
     * Top-left corner on screen for an element of the given scaled size, kept inside the screen.
     * The element is aligned to its anchor (a right anchor right-aligns it, etc.).
     */
    public int x(int screenW, int scaledW) {
        int dir = anchor.ax == 1 ? -1 : 1;
        int x = Math.round(anchor.ax * (screenW - scaledW)) + dir * offsetX;
        return clamp(x, 0, Math.max(0, screenW - scaledW));
    }

    public int y(int screenH, int scaledH) {
        int dir = anchor.ay == 1 ? -1 : 1;
        int y = Math.round(anchor.ay * (screenH - scaledH)) + dir * offsetY;
        return clamp(y, 0, Math.max(0, screenH - scaledH));
    }

    /**
     * Re-anchors the element so its top-left corner lands at (x, y): picks the anchor of the
     * screen third holding the element's centre, then derives offsets from it.
     */
    public void moveTo(int x, int y, int scaledW, int scaledH, int screenW, int screenH) {
        x = clamp(x, 0, Math.max(0, screenW - scaledW));
        y = clamp(y, 0, Math.max(0, screenH - scaledH));
        anchor = Anchor.nearest(x + scaledW / 2.0, y + scaledH / 2.0, screenW, screenH);
        int baseX = Math.round(anchor.ax * (screenW - scaledW));
        int baseY = Math.round(anchor.ay * (screenH - scaledH));
        offsetX = anchor.ax == 1 ? baseX - x : x - baseX;
        offsetY = anchor.ay == 1 ? baseY - y : y - baseY;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("anchor", anchor.name());
        m.put("x", offsetX);
        m.put("y", offsetY);
        m.put("scale", scale);
        m.put("opacity", opacity);
        m.put("background", background);
        m.put("backgroundColor", ColorSetting.toHex(backgroundColor));
        m.put("textColor", ColorSetting.toHex(textColor));
        m.put("accentColor", ColorSetting.toHex(accentColor));
        m.put("shadow", shadow);
        return m;
    }

    /** Applies saved values on top of the current ones; unknown or invalid entries are ignored. */
    public void fromJson(Map<?, ?> m) {
        if (m == null) return;
        if (m.get("anchor") instanceof String s) {
            for (Anchor a : Anchor.values()) if (a.name().equals(s)) anchor = a;
        }
        if (m.get("x") instanceof Number n) offsetX = n.intValue();
        if (m.get("y") instanceof Number n) offsetY = n.intValue();
        if (m.get("scale") instanceof Number n) setScale(n.floatValue());
        if (m.get("opacity") instanceof Number n) setOpacity(n.floatValue());
        if (m.get("background") instanceof Boolean b) background = b;
        if (m.get("shadow") instanceof Boolean b) shadow = b;
        Integer c;
        if ((c = color(m.get("backgroundColor"))) != null) backgroundColor = c;
        if ((c = color(m.get("textColor"))) != null) textColor = c;
        if ((c = color(m.get("accentColor"))) != null) accentColor = c;
    }

    private static Integer color(Object o) {
        return o instanceof String s ? ColorSetting.parseHex(s) : null;
    }
}
