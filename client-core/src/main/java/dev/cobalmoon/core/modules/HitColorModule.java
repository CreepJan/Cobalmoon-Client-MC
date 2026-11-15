/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.modules;

import dev.cobalmoon.api.event.Events;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.ColorSetting;
import net.minecraft.client.Minecraft;

/** Changes the colour entities flash when they take damage (vanilla: translucent red). */
public final class HitColorModule extends Module {
    /** Vanilla hurt overlay colour (ARGB). */
    public static final int VANILLA = 0xB2FF0000;

    /** Implemented on the overlay texture by a mixin. */
    public interface Target {
        void cobalmoon$paintHurt(int argb);
    }

    private final ColorSetting color = setting(new ColorSetting("color", "cobalmoon.setting.hitcolor.color", 0x9952E0D0));
    private boolean dirty = true;

    public HitColorModule() {
        super("hit_color", "cobalmoon.module.hit_color", "cobalmoon.module.hit_color.desc", Category.VISUAL);
        color.onChange(c -> dirty = true);
        on(Events.ClientTick.class, e -> applyIfNeeded());
    }

    @Override
    protected void onEnable() {
        dirty = true;
    }

    @Override
    protected void onDisable() {
        paint(VANILLA);
    }

    private void applyIfNeeded() {
        if (dirty && paint(color.get())) dirty = false;
    }

    /** Repaints the overlay texture; false if the renderer does not exist yet. */
    private static boolean paint(int argb) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) return false;
        if (mc.gameRenderer.overlayTexture() instanceof Target t) {
            t.cobalmoon$paintHurt(argb);
            return true;
        }
        return false;
    }
}
