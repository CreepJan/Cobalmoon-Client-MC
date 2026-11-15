/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.modules;

import dev.cobalmoon.api.event.Events;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.BooleanSetting;
import dev.cobalmoon.api.setting.EnumSetting;
import dev.cobalmoon.api.setting.NumberSetting;
import dev.cobalmoon.core.CobalmoonClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/** Zoom (default key C): hold or toggle, smooth, mouse wheel adjusts while zoomed. */
public final class ZoomModule extends Module {
    public enum Mode { HOLD, TOGGLE }

    private static volatile ZoomModule instance;

    private final NumberSetting factor = setting(new NumberSetting("factor", "cobalmoon.setting.zoom.factor", 4, 1.5, 15, 0.5));
    private final EnumSetting<Mode> mode = setting(new EnumSetting<>("mode", "cobalmoon.setting.zoom.mode", Mode.HOLD,
            m -> "cobalmoon.zoom.mode." + m.name().toLowerCase(Locale.ROOT)));
    private final BooleanSetting smooth = setting(new BooleanSetting("smooth", "cobalmoon.setting.zoom.smooth", true));
    private final BooleanSetting scroll = setting(new BooleanSetting("scroll", "cobalmoon.setting.zoom.scroll", true));
    private final BooleanSetting cinematic = setting(new BooleanSetting("cinematic", "cobalmoon.setting.zoom.cinematic", false));

    private KeyMapping key;
    private boolean active;
    private boolean toggled;
    private double scrollFactor = 1;
    private double current = 1;
    private long lastFrame;
    private boolean cinematicSet;

    public ZoomModule() {
        super("zoom", "cobalmoon.module.zoom", "cobalmoon.module.zoom.desc", Category.VISUAL);
        instance = this;
        on(Events.ClientTick.class, e -> tick());
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    public void bindKey(KeyMapping key) {
        this.key = key;
    }

    private void tick() {
        if (key == null) return;
        if (mode.get() == Mode.TOGGLE) {
            while (key.consumeClick()) toggled = !toggled;
        } else {
            while (key.consumeClick()) { /* drain */ }
            toggled = key.isDown();
        }
        boolean was = active;
        active = toggled && Minecraft.getInstance().player != null;
        if (active && !was) scrollFactor = 1;
        Minecraft mc = Minecraft.getInstance();
        if (cinematic.get() && active != cinematicSet) {
            mc.options.smoothCamera = active;
            cinematicSet = active;
        }
    }

    @Override
    protected void onDisable() {
        active = false;
        toggled = false;
        current = 1;
        if (cinematicSet) {
            Minecraft.getInstance().options.smoothCamera = false;
            cinematicSet = false;
        }
    }

    double target() {
        return active ? Math.max(1, factor.get() * scrollFactor) : 1;
    }

    /** Applied to the game's field of view every frame by the version-specific FOV mixin. */
    public static float fov(float base) {
        ZoomModule z = instance;
        if (z == null || !z.isEnabled()) return base;
        long now = System.nanoTime();
        double dt = z.lastFrame == 0 ? 0.016 : Math.min(0.1, (now - z.lastFrame) / 1e9);
        z.lastFrame = now;
        double t = z.target();
        if (!z.smooth.get() || CobalmoonClient.reduceMotion()) z.current = t;
        else z.current += (t - z.current) * (1 - Math.exp(-dt * 14));
        if (Math.abs(z.current - 1) < 1e-3) return base;
        return (float) (base / z.current);
    }

    /** Mouse wheel while zoomed: returns true if consumed. */
    public static boolean consumeScroll(double dy) {
        ZoomModule z = instance;
        if (z == null || !z.isEnabled() || !z.active || !z.scroll.get() || dy == 0) return false;
        z.scrollFactor = Math.max(0.4, Math.min(4, z.scrollFactor * (dy > 0 ? 1.15 : 1 / 1.15)));
        return true;
    }

    public static boolean isZooming() {
        ZoomModule z = instance;
        return z != null && z.isEnabled() && z.active;
    }
}
