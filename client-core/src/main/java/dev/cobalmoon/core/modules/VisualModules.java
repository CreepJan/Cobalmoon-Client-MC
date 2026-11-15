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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;

import java.util.Locale;

/** Visual tweaks whose hooks live in version-specific mixins; the logic is shared here. */
public final class VisualModules {
    private VisualModules() {}

    /** Lowers the first-person fire overlay. */
    public static final class LowFire extends Module {
        private static volatile LowFire instance;
        private final NumberSetting height = setting(new NumberSetting("height", "cobalmoon.setting.low.height", 0.3, 0, 0.6, 0.05));

        public LowFire() {
            super("low_fire", "cobalmoon.module.low_fire", "cobalmoon.module.low_fire.desc", Category.VISUAL);
            instance = this;
        }

        /** Downward offset (in screen-space units) applied before the fire overlay is drawn. */
        public static float offset() {
            LowFire m = instance;
            return m != null && m.isEnabled() ? m.height.get().floatValue() : 0f;
        }
    }

    /** Lowers the shield in first person so it covers less of the screen. */
    public static final class LowShield extends Module {
        private static volatile LowShield instance;
        private final NumberSetting height = setting(new NumberSetting("height", "cobalmoon.setting.low.height", 0.2, 0, 0.6, 0.05));

        public LowShield() {
            super("low_shield", "cobalmoon.module.low_shield", "cobalmoon.module.low_shield.desc", Category.VISUAL);
            instance = this;
        }

        public static float offset() {
            LowShield m = instance;
            return m != null && m.isEnabled() ? m.height.get().floatValue() : 0f;
        }
    }

    /** Removes distance fog (the haze at the edge of the render distance). Fog under water, in lava,
     *  blindness and similar effects is left untouched. */
    public static final class NoFog extends Module {
        private static volatile NoFog instance;

        public NoFog() {
            super("no_fog", "cobalmoon.module.no_fog", "cobalmoon.module.no_fog.desc", Category.VISUAL);
            instance = this;
        }

        public static void apply(FogData fog) {
            NoFog m = instance;
            if (m == null || !m.isEnabled()) return;
            fog.renderDistanceStart = 100_000f;
            fog.renderDistanceEnd = 100_001f;
        }
    }

    /** Extra hit particles on confirmed hits. */
    public static final class Particles extends Module {
        private final NumberSetting multiplier = setting(new NumberSetting("multiplier", "cobalmoon.setting.particles.multiplier", 2, 1, 5, 1));
        private final BooleanSetting alwaysSharpness = setting(new BooleanSetting("always_sharpness", "cobalmoon.setting.particles.always_sharpness", false));
        private final BooleanSetting alwaysCrit = setting(new BooleanSetting("always_crit", "cobalmoon.setting.particles.always_crit", false));

        public Particles() {
            super("particles", "cobalmoon.module.particles", "cobalmoon.module.particles.desc", Category.VISUAL);
            on(Events.HitConfirmed.class, e -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level == null) return;
                Entity target = mc.level.getEntity(e.entityId());
                if (target == null) return;
                // Vanilla already spawns one burst on a real critical hit; add the rest.
                int critBursts = e.critical() ? multiplier.intValue() - 1 : (alwaysCrit.get() ? multiplier.intValue() : 0);
                for (int i = 0; i < critBursts; i++) mc.particleEngine.createTrackingEmitter(target, ParticleTypes.CRIT);
                if (alwaysSharpness.get()) {
                    for (int i = 0; i < multiplier.intValue(); i++) mc.particleEngine.createTrackingEmitter(target, ParticleTypes.ENCHANTED_HIT);
                }
            });
        }
    }

    /** Plays a sound when one of your hits lands. */
    public static final class Hitsounds extends Module {
        public enum Sound { ORB, BELL, PLING, HAT, ARROW, CUSTOM }

        private final EnumSetting<Sound> sound = setting(new EnumSetting<>("sound", "cobalmoon.setting.hitsounds.sound", Sound.ORB,
                s -> "cobalmoon.hitsounds.sound." + s.name().toLowerCase(Locale.ROOT)));
        private final NumberSetting volume = setting(new NumberSetting("volume", "cobalmoon.setting.hitsounds.volume", 0.6, 0.05, 1, 0.05));
        private final NumberSetting pitch = setting(new NumberSetting("pitch", "cobalmoon.setting.hitsounds.pitch", 1.0, 0.5, 2, 0.05));
        private final BooleanSetting playersOnly = setting(new BooleanSetting("players_only", "cobalmoon.setting.hitsounds.players_only", true));
        private final BooleanSetting critPitch = setting(new BooleanSetting("crit_pitch", "cobalmoon.setting.hitsounds.crit_pitch", true));

        /** Resource packs can replace this sound (assets/cobalmoon/sounds.json → "hitsound.custom"). */
        static final SoundEvent CUSTOM = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("cobalmoon", "hitsound.custom"));

        public Hitsounds() {
            super("hitsounds", "cobalmoon.module.hitsounds", "cobalmoon.module.hitsounds.desc", Category.VISUAL);
            on(Events.HitConfirmed.class, e -> {
                if (playersOnly.get() && !e.targetIsPlayer()) return;
                float p = pitch.get().floatValue() * (critPitch.get() && e.critical() ? 1.25f : 1f);
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event(sound.get()), p, volume.get().floatValue()));
            });
        }

        static SoundEvent event(Sound s) {
            return switch (s) {
                case ORB -> SoundEvents.EXPERIENCE_ORB_PICKUP;
                case BELL -> SoundEvents.NOTE_BLOCK_BELL.value();
                case PLING -> SoundEvents.NOTE_BLOCK_PLING.value();
                case HAT -> SoundEvents.NOTE_BLOCK_HAT.value();
                case ARROW -> SoundEvents.ARROW_HIT_PLAYER;
                case CUSTOM -> CUSTOM;
            };
        }
    }
}
