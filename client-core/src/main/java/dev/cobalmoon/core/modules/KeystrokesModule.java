/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.modules;

import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.HudCanvas;
import dev.cobalmoon.api.hud.HudElement;
import dev.cobalmoon.api.hud.HudLayout;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.BooleanSetting;
import dev.cobalmoon.api.setting.NumberSetting;
import dev.cobalmoon.api.theme.DefaultTokens;
import dev.cobalmoon.core.CobalmoonClient;
import dev.cobalmoon.core.input.Clicks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/** WASD, mouse buttons and space bar, lit up while pressed (shows your real bindings). */
public final class KeystrokesModule extends Module {
    private final NumberSetting keySize = setting(new NumberSetting("key_size", "cobalmoon.setting.keystrokes.key_size", 22, 14, 36, 1));
    private final NumberSetting gap = setting(new NumberSetting("gap", "cobalmoon.setting.keystrokes.gap", 2, 0, 6, 1));
    private final BooleanSetting showMouse = setting(new BooleanSetting("show_mouse", "cobalmoon.setting.keystrokes.show_mouse", true));
    private final BooleanSetting showCps = setting(new BooleanSetting("show_cps", "cobalmoon.setting.keystrokes.show_cps", true));
    private final BooleanSetting showSpace = setting(new BooleanSetting("show_space", "cobalmoon.setting.keystrokes.show_space", true));
    private final NumberSetting fadeMs = setting(new NumberSetting("fade_ms", "cobalmoon.setting.keystrokes.fade_ms", 80, 0, 300, 10));

    /** Per-key highlight level 0..1, eased towards the pressed state. */
    private final float[] level = new float[7];
    private long lastFrame;

    public KeystrokesModule() {
        super("keystrokes", "cobalmoon.module.keystrokes", "cobalmoon.module.keystrokes.desc", Category.HUD);
        HudLayout defaults = new HudLayout(Anchor.BOTTOM_RIGHT, 4, 4);
        defaults.background = false;
        hud(new Element(defaults));
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    private final class Element extends HudElement {
        Element(HudLayout defaults) {
            super("keystrokes", "cobalmoon.module.keystrokes", defaults);
        }

        @Override public boolean supportsBackground() { return false; }

        int s() { return keySize.intValue(); }

        int g() { return gap.intValue(); }

        @Override
        public int width(HudCanvas c, boolean preview) {
            return s() * 3 + g() * 2;
        }

        @Override
        public int height(HudCanvas c, boolean preview) {
            int h = s() * 2 + g();
            if (showMouse.get()) h += g() + s();
            if (showSpace.get()) h += g() + Math.max(8, s() / 2);
            return h;
        }

        @Override
        public void render(HudCanvas c, boolean preview) {
            Options o = Minecraft.getInstance().options;
            boolean[] down = {
                    o.keyUp.isDown(), o.keyLeft.isDown(), o.keyDown.isDown(), o.keyRight.isDown(),
                    o.keyAttack.isDown(), o.keyUse.isDown(), o.keyJump.isDown()
            };
            long now = System.nanoTime();
            float dt = lastFrame == 0 ? 1 : (now - lastFrame) / 1_000_000f;
            lastFrame = now;
            float step = fadeMs.get() <= 0 || CobalmoonClient.reduceMotion() ? 1f : Math.min(1f, dt / fadeMs.get().floatValue());
            for (int i = 0; i < level.length; i++) {
                float target = down[i] ? 1f : 0f;
                level[i] += Math.signum(target - level[i]) * Math.min(Math.abs(target - level[i]), step);
            }

            int s = s(), g = g();
            HudLayout l = layout();
            key(c, s + g, 0, s, s, label(o.keyUp), level[0], l);
            key(c, 0, s + g, s, s, label(o.keyLeft), level[1], l);
            key(c, s + g, s + g, s, s, label(o.keyDown), level[2], l);
            key(c, (s + g) * 2, s + g, s, s, label(o.keyRight), level[3], l);
            int y = (s + g) * 2;
            int full = s * 3 + g * 2;
            if (showMouse.get()) {
                int half = (full - g) / 2;
                String lmb = showCps.get() ? Clicks.LEFT.cps() + " CPS" : "LMB";
                String rmb = showCps.get() ? Clicks.RIGHT.cps() + " CPS" : "RMB";
                key(c, 0, y, half, s, lmb, level[4], l);
                key(c, half + g, y, full - half - g, s, rmb, level[5], l);
                y += s + g;
            }
            if (showSpace.get()) {
                int h = Math.max(8, s / 2);
                key(c, 0, y, full, h, null, level[6], l);
                int barW = Math.max(6, full / 3);
                int barColor = blend(l.textColor, DefaultTokens.ON_ACCENT, level[6]);
                c.fill(full / 2 - barW / 2, y + h / 2, full / 2 + barW / 2, y + h / 2 + 1, barColor);
            }
        }

        private void key(HudCanvas c, int x, int y, int w, int h, String text, float lvl, HudLayout l) {
            int bg = blend(l.backgroundColor, l.accentColor, lvl);
            c.fill(x, y, x + w, y + h, bg);
            if (text != null) {
                int color = blend(l.textColor, DefaultTokens.ON_ACCENT, lvl);
                String t = c.textWidth(text) > w - 2 ? text.substring(0, Math.max(1, Math.min(text.length(), 3))) : text;
                c.centeredText(t, x + w / 2, y + (h - c.fontHeight()) / 2 + 1, color, l.shadow && lvl < 0.5f);
            }
        }
    }

    static String label(KeyMapping key) {
        String s = key.getTranslatedKeyMessage().getString();
        return s.length() > 5 ? s.substring(0, 5) : s;
    }

    /** Linear blend of two ARGB colours, t in [0, 1]. */
    public static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }
}
