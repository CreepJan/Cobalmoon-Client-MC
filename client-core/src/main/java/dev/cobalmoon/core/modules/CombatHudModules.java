/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.modules;

import dev.cobalmoon.api.event.Events;
import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.TextHudElement;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.BooleanSetting;
import dev.cobalmoon.api.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.List;
import java.util.Locale;

/** Small text HUD modules built on the combat events. */
public final class CombatHudModules {
    private CombatHudModules() {}

    static long now() {
        return System.nanoTime();
    }

    /** Latency to the server, from the tab list. */
    public static final class Ping extends Module {
        public Ping() {
            super("ping", "cobalmoon.module.ping", "cobalmoon.module.ping.desc", Category.HUD);
            hud(new TextHudElement("ping", "cobalmoon.module.ping", Anchor.TOP_LEFT, 4, 92) {
                @Override
                protected List<String> lines(boolean preview) {
                    return List.of(labeled("Ping", ping() + " ms"));
                }
            });
        }

        static int ping() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null) return 0;
            PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            return info != null ? info.getLatency() : 0;
        }
    }

    /**
     * Consecutive confirmed hits without being hit back. Resets when you take damage or after
     * {@code reset_seconds} without landing a hit.
     */
    public static final class Combo extends Module {
        private final NumberSetting resetSeconds = setting(new NumberSetting("reset_seconds", "cobalmoon.setting.combo.reset_seconds", 3, 1, 10, 0.5));
        private final BooleanSetting hideZero = setting(new BooleanSetting("hide_zero", "cobalmoon.setting.hide_zero", false));
        private final ComboCounter counter = new ComboCounter();

        public Combo() {
            super("combo", "cobalmoon.module.combo", "cobalmoon.module.combo.desc", Category.HUD);
            hud(new TextHudElement("combo", "cobalmoon.module.combo", Anchor.CENTER, 0, 26) {
                @Override
                protected boolean shouldRender() {
                    return !hideZero.get() || counter.value(now(), seconds()) > 0;
                }

                @Override
                protected List<String> lines(boolean preview) {
                    return List.of(labeled("Combo", Integer.toString(counter.value(now(), seconds()))));
                }
            });
            on(Events.HitConfirmed.class, e -> counter.hit(e.entityId(), now(), seconds()));
            on(Events.PlayerHurt.class, e -> counter.reset());
            on(Events.ServerLeave.class, e -> counter.reset());
        }

        private long seconds() {
            return (long) (resetSeconds.get() * 1_000_000_000L);
        }
    }

    /** Pure combo logic (tested without Minecraft). */
    public static final class ComboCounter {
        private int count;
        private int target = Integer.MIN_VALUE;
        private long lastHit;

        /** A confirmed hit on {@code entityId}; hitting someone else starts a new combo. */
        public void hit(int entityId, long nanos, long timeoutNanos) {
            if (entityId != target || nanos - lastHit > timeoutNanos) count = 0;
            target = entityId;
            lastHit = nanos;
            count++;
        }

        public void reset() {
            count = 0;
            target = Integer.MIN_VALUE;
        }

        public int value(long nanos, long timeoutNanos) {
            return count > 0 && nanos - lastHit <= timeoutNanos ? count : 0;
        }
    }

    /** Distance of your last confirmed hit (eyes to the target's hitbox). */
    public static final class Reach extends Module {
        private final NumberSetting decimals = setting(new NumberSetting("decimals", "cobalmoon.setting.coords.decimals", 2, 0, 3, 1));
        private final NumberSetting fadeSeconds = setting(new NumberSetting("fade_seconds", "cobalmoon.setting.reach.fade_seconds", 3, 0, 30, 1));
        private double last = -1;
        private long at;

        public Reach() {
            super("reach", "cobalmoon.module.reach", "cobalmoon.module.reach.desc", Category.HUD);
            hud(new TextHudElement("reach", "cobalmoon.module.reach", Anchor.CENTER, 0, 44) {
                @Override
                protected List<String> lines(boolean preview) {
                    boolean fresh = last >= 0 && (fadeSeconds.get() == 0 || now() - at < fadeSeconds.get() * 1e9);
                    String v = fresh ? String.format(Locale.ROOT, "%." + decimals.intValue() + "f", last) : (preview ? "2.95" : "-");
                    return List.of(labeled("Reach", v));
                }
            });
            on(Events.HitConfirmed.class, e -> {
                last = e.distance();
                at = now();
            });
        }
    }

    /** Critical hits landed since joining the world. */
    public static final class Crits extends Module {
        private int crits;
        private int hits;

        public Crits() {
            super("crits", "cobalmoon.module.crits", "cobalmoon.module.crits.desc", Category.HUD);
            hud(new TextHudElement("crits", "cobalmoon.module.crits", Anchor.CENTER, 0, 62) {
                @Override
                protected List<String> lines(boolean preview) {
                    int pct = hits == 0 ? 0 : Math.round(100f * crits / hits);
                    return List.of(labeled("Crits", crits + " (" + pct + "%)"));
                }
            });
            on(Events.HitConfirmed.class, e -> {
                hits++;
                if (e.critical()) crits++;
            });
            on(Events.ServerLeave.class, e -> {
                crits = 0;
                hits = 0;
            });
        }
    }
}
