/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.modules;

import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.HudCanvas;
import dev.cobalmoon.api.hud.HudElement;
import dev.cobalmoon.api.hud.HudLayout;
import dev.cobalmoon.api.hud.TextHudElement;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.BooleanSetting;
import dev.cobalmoon.api.setting.EnumSetting;
import dev.cobalmoon.api.setting.NumberSetting;
import dev.cobalmoon.api.theme.DefaultTokens;
import dev.cobalmoon.core.render.GuiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** HUD modules that show the player's own state: armour, effects, attack cooldown and timers. */
public final class StatusHudModules {
    private StatusHudModules() {}

    /** "1:05" for ticks; "∞" for infinite effects. */
    public static String formatTicks(int ticks) {
        if (ticks < 0) return "∞";
        int s = (ticks + 19) / 20;
        return s >= 60 ? String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) : s + "s";
    }

    public static String roman(int amplifier) {
        String[] r = {"", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"};
        return amplifier >= 0 && amplifier < r.length ? r[amplifier] : " " + (amplifier + 1);
    }

    /** Durability colour from green (full) through yellow to red (about to break). */
    public static int durabilityColor(float fraction) {
        float f = Math.max(0, Math.min(1, fraction));
        int r = (int) (255 * Math.min(1, 2 * (1 - f)));
        int g = (int) (255 * Math.min(1, 2 * f));
        return 0xFF000000 | (r << 16) | (g << 8) | 0x40;
    }

    // -----------------------------------------------------------------------------------------

    /** Armour pieces and held item, with durability. */
    public static final class Armor extends Module {
        public enum Display { PERCENT, REMAINING, NONE }

        private final BooleanSetting horizontal = setting(new BooleanSetting("horizontal", "cobalmoon.setting.armor.horizontal", false));
        private final BooleanSetting showHand = setting(new BooleanSetting("show_hand", "cobalmoon.setting.armor.show_hand", true));
        private final EnumSetting<Display> display = setting(new EnumSetting<>("display", "cobalmoon.setting.armor.display", Display.PERCENT,
                d -> "cobalmoon.armor.display." + d.name().toLowerCase(Locale.ROOT)));

        public Armor() {
            super("armor", "cobalmoon.module.armor", "cobalmoon.module.armor.desc", Category.HUD);
            HudLayout l = new HudLayout(Anchor.CENTER_RIGHT, 4, 0);
            l.background = false;
            hud(new Element(l));
        }

        private List<ItemStack> stacks(boolean preview) {
            Player p = Minecraft.getInstance().player;
            List<ItemStack> out = new ArrayList<>();
            if (p == null) {
                if (preview) {
                    out.add(new ItemStack(Items.DIAMOND_HELMET));
                    out.add(new ItemStack(Items.DIAMOND_CHESTPLATE));
                    out.add(new ItemStack(Items.IRON_LEGGINGS));
                    out.add(new ItemStack(Items.DIAMOND_BOOTS));
                    if (showHand.get()) out.add(new ItemStack(Items.DIAMOND_SWORD));
                }
                return out;
            }
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack s = p.getItemBySlot(slot);
                if (!s.isEmpty()) out.add(s);
            }
            if (showHand.get() && !p.getMainHandItem().isEmpty()) out.add(p.getMainHandItem());
            if (out.isEmpty() && preview) out.add(new ItemStack(Items.DIAMOND_CHESTPLATE));
            return out;
        }

        private String label(ItemStack s) {
            if (display.get() == Display.NONE) return "";
            if (!s.isDamageableItem()) return s.getCount() > 1 ? Integer.toString(s.getCount()) : "";
            int left = s.getMaxDamage() - s.getDamageValue();
            return display.get() == Display.PERCENT ? Math.round(100f * left / s.getMaxDamage()) + "%" : Integer.toString(left);
        }

        private final class Element extends HudElement {
            Element(HudLayout l) {
                super("armor", "cobalmoon.module.armor", l);
            }

            @Override public boolean supportsBackground() { return true; }

            private int cellW(HudCanvas c, boolean preview) {
                int w = 16;
                for (ItemStack s : stacks(preview)) w = Math.max(w, 18 + c.textWidth(label(s)));
                return w;
            }

            @Override
            public int width(HudCanvas c, boolean preview) {
                int n = Math.max(1, stacks(preview).size());
                return horizontal.get() ? n * (cellW(c, preview) + 2) : cellW(c, preview) + 4;
            }

            @Override
            public int height(HudCanvas c, boolean preview) {
                int n = Math.max(1, stacks(preview).size());
                return horizontal.get() ? 20 : n * 18 + 2;
            }

            @Override
            public void render(HudCanvas c, boolean preview) {
                int i = 0;
                int cw = cellW(c, preview);
                for (ItemStack s : stacks(preview)) {
                    int x = horizontal.get() ? 2 + i * (cw + 2) : 2;
                    int y = horizontal.get() ? 2 : 1 + i * 18;
                    if (c instanceof GuiCanvas g) g.item(s, x, y);
                    String text = label(s);
                    int color = s.isDamageableItem()
                            ? durabilityColor(1f - (float) s.getDamageValue() / Math.max(1, s.getMaxDamage()))
                            : layout().textColor;
                    c.text(text, x + 18, y + 4, color, layout().shadow);
                    i++;
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------

    /** Active potion effects with level and time left. */
    public static final class Potions extends Module {
        private final BooleanSetting effectColors = setting(new BooleanSetting("effect_colors", "cobalmoon.setting.potions.effect_colors", true));

        public Potions() {
            super("potions", "cobalmoon.module.potions", "cobalmoon.module.potions.desc", Category.HUD);
            hud(new TextHudElement("potions", "cobalmoon.module.potions", Anchor.TOP_RIGHT, 4, 40) {
                @Override
                protected boolean shouldRender() {
                    Player p = Minecraft.getInstance().player;
                    return p != null && !p.getActiveEffects().isEmpty();
                }

                @Override
                protected List<String> lines(boolean preview) {
                    Player p = Minecraft.getInstance().player;
                    List<String> out = new ArrayList<>();
                    if (p != null) {
                        p.getActiveEffects().stream()
                                .sorted(Comparator.comparingInt(MobEffectInstance::getDuration))
                                .forEach(e -> out.add(labeled(e.getEffect().value().getDisplayName().getString() + roman(e.getAmplifier()),
                                        formatTicks(e.isInfiniteDuration() ? -1 : e.getDuration()))));
                    }
                    if (out.isEmpty() && preview) {
                        out.add(labeled("Speed II", "1:24"));
                        out.add(labeled("Fire Resistance", "7:52"));
                    }
                    return out;
                }

                @Override
                public void render(HudCanvas c, boolean preview) {
                    if (!effectColors.get()) {
                        super.render(c, preview);
                        return;
                    }
                    // Labels in each effect's own colour (lightened so dark colours stay readable).
                    Player p = Minecraft.getInstance().player;
                    List<MobEffectInstance> effects = p == null ? List.of()
                            : p.getActiveEffects().stream().sorted(Comparator.comparingInt(MobEffectInstance::getDuration)).toList();
                    if (effects.isEmpty()) {
                        super.render(c, preview);
                        return;
                    }
                    int y = PADDING;
                    for (MobEffectInstance e : effects) {
                        String name = e.getEffect().value().getDisplayName().getString() + roman(e.getAmplifier()) + " ";
                        int color = KeystrokesModule.blend(0xFF000000 | e.getEffect().value().getColor(), DefaultTokens.STONE, 0.35f);
                        c.text(name, PADDING, y, color, layout().shadow);
                        c.text(formatTicks(e.isInfiniteDuration() ? -1 : e.getDuration()), PADDING + c.textWidth(name), y, layout().textColor, layout().shadow);
                        y += c.fontHeight() + LINE_SPACING;
                    }
                }
            });
        }
    }

    // -----------------------------------------------------------------------------------------

    /** Attack cooldown (weapon charge) as a bar. */
    public static final class Cooldown extends Module {
        private final BooleanSetting hideWhenReady = setting(new BooleanSetting("hide_ready", "cobalmoon.setting.cooldown.hide_ready", true));
        private final NumberSetting width = setting(new NumberSetting("width", "cobalmoon.setting.cooldown.width", 40, 16, 120, 2));

        public Cooldown() {
            super("cooldown", "cobalmoon.module.cooldown", "cobalmoon.module.cooldown.desc", Category.HUD);
            HudLayout l = new HudLayout(Anchor.CENTER, 0, 14);
            hud(new HudElement("cooldown", "cobalmoon.module.cooldown", l) {
                float strength(boolean preview) {
                    Player p = Minecraft.getInstance().player;
                    return p == null ? (preview ? 0.6f : 1f) : p.getAttackStrengthScale(0f);
                }

                @Override
                protected boolean shouldRender() {
                    return !hideWhenReady.get() || strength(false) < 1f;
                }

                @Override public int width(HudCanvas c, boolean preview) { return width.intValue() + 4; }

                @Override public int height(HudCanvas c, boolean preview) { return 7; }

                @Override
                public void render(HudCanvas c, boolean preview) {
                    float s = Math.max(0, Math.min(1, strength(preview)));
                    int w = width.intValue();
                    c.fill(2, 2, 2 + Math.round(w * s), 5, s >= 1f ? layout().accentColor : layout().textColor);
                }
            });
        }
    }

    // -----------------------------------------------------------------------------------------

    /** Ender pearl and golden apple cooldowns (as the server sets them) and the nearest lit TNT. */
    public static final class Timers extends Module {
        private final NumberSetting tntRadius = setting(new NumberSetting("tnt_radius", "cobalmoon.setting.timers.tnt_radius", 12, 4, 32, 1));

        public Timers() {
            super("timers", "cobalmoon.module.timers", "cobalmoon.module.timers.desc", Category.HUD);
            hud(new TextHudElement("timers", "cobalmoon.module.timers", Anchor.TOP_RIGHT, 4, 4) {
                @Override
                protected boolean shouldRender() {
                    return !lines(false).isEmpty();
                }

                @Override
                protected List<String> lines(boolean preview) {
                    Minecraft mc = Minecraft.getInstance();
                    Player p = mc.player;
                    List<String> out = new ArrayList<>();
                    if (p != null && mc.level != null) {
                        cooldown(out, p, new ItemStack(Items.ENDER_PEARL), "Pearl");
                        cooldown(out, p, new ItemStack(Items.GOLDEN_APPLE), "Gapple");
                        cooldown(out, p, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), "God apple");
                        double r = tntRadius.get();
                        mc.level.getEntitiesOfClass(PrimedTnt.class, p.getBoundingBox().inflate(r)).stream()
                                .min(Comparator.comparingDouble(t -> t.distanceToSqr(p)))
                                .ifPresent(t -> out.add(labeled("TNT", String.format(Locale.ROOT, "%.1fs", t.getFuse() / 20.0))));
                    }
                    if (out.isEmpty() && preview) {
                        out.add(labeled("Pearl", "62%"));
                        out.add(labeled("TNT", "2.4s"));
                    }
                    return out;
                }
            });
        }

        private static void cooldown(List<String> out, Player p, ItemStack stack, String label) {
            float pct = p.getCooldowns().getCooldownPercent(stack, 0f);
            if (pct > 0f) out.add(label + TextHudElement.LABEL_SEPARATOR + Math.round(pct * 100) + "%");
        }
    }
}
