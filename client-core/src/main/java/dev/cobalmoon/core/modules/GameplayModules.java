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
import dev.cobalmoon.api.setting.StringSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Quality-of-life gameplay modules. None of them act on their own: they only change how your own input works. */
public final class GameplayModules {
    private GameplayModules() {}

    /**
     * Toggle sprint / toggle sneak, using Minecraft's own "Toggle" options (so behaviour is exactly vanilla's),
     * plus an optional status line. Your previous options are restored when the module is turned off.
     */
    public static final class ToggleSprint extends Module {
        private final BooleanSetting sprint = setting(new BooleanSetting("sprint", "cobalmoon.setting.toggle.sprint", true));
        private final BooleanSetting sneak = setting(new BooleanSetting("sneak", "cobalmoon.setting.toggle.sneak", false));
        private Boolean savedSprint;
        private Boolean savedSneak;
        /** Enabled before Minecraft created its options (saved config at startup): apply on the first tick. */
        private boolean pending;

        public ToggleSprint() {
            super("toggle_sprint", "cobalmoon.module.toggle_sprint", "cobalmoon.module.toggle_sprint.desc", Category.GAMEPLAY);
            sprint.onChange(v -> apply());
            sneak.onChange(v -> apply());
            on(Events.ClientTick.class, e -> {
                if (pending && Minecraft.getInstance().options != null) start();
            });
            hud(new TextHudElement("toggle_sprint", "cobalmoon.module.toggle_sprint", Anchor.BOTTOM_LEFT, 4, 4) {
                @Override
                protected boolean shouldRender() {
                    Player p = Minecraft.getInstance().player;
                    return p != null && (p.isSprinting() || p.isShiftKeyDown());
                }

                @Override
                protected List<String> lines(boolean preview) {
                    Player p = Minecraft.getInstance().player;
                    if (p != null && p.isShiftKeyDown()) return List.of("[Sneak]");
                    return List.of("[Sprint]");
                }
            });
        }

        @Override
        protected void onEnable() {
            if (Minecraft.getInstance().options == null) {
                pending = true;
                return;
            }
            start();
        }

        private void start() {
            pending = false;
            Options o = Minecraft.getInstance().options;
            savedSprint = o.toggleSprint().get();
            savedSneak = o.toggleCrouch().get();
            apply();
        }

        private void apply() {
            Options o = Minecraft.getInstance().options;
            if (!isEnabled() || pending || o == null) return;
            o.toggleSprint().set(sprint.get());
            o.toggleCrouch().set(sneak.get());
        }

        @Override
        protected void onDisable() {
            pending = false;
            Options o = Minecraft.getInstance().options;
            if (o == null) return;
            if (savedSprint != null) o.toggleSprint().set(savedSprint);
            if (savedSneak != null) o.toggleCrouch().set(savedSneak);
            savedSprint = null;
            savedSneak = null;
        }
    }

    /**
     * Keys that select the hotbar slot holding a kind of item (weapon, pearl, golden apple, blocks,
     * bow/rod) and a key to go back to the previous slot. Unbound by default: set them in Controls.
     * They only change the selected slot, exactly like pressing 1-9.
     */
    public static final class HotbarBinds extends Module {
        public enum Kind { WEAPON, PEARL, GAPPLE, BLOCKS, RANGED, PREVIOUS }

        private final List<KeyMapping> keys = new ArrayList<>();
        private int previous = -1;
        private int lastSeen = -1;

        public HotbarBinds() {
            super("hotbar_binds", "cobalmoon.module.hotbar_binds", "cobalmoon.module.hotbar_binds.desc", Category.GAMEPLAY);
            on(Events.ClientTick.class, e -> tick());
        }

        public static String keyName(Kind k) {
            return "key.cobalmoon.slot." + k.name().toLowerCase(Locale.ROOT);
        }

        public void bindKeys(List<KeyMapping> mappings) {
            keys.clear();
            keys.addAll(mappings);
        }

        static Predicate<ItemStack> matcher(Kind k) {
            return switch (k) {
                case WEAPON -> s -> s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES) || s.is(Items.MACE);
                case PEARL -> s -> s.is(Items.ENDER_PEARL);
                case GAPPLE -> s -> s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE);
                case BLOCKS -> s -> s.getItem() instanceof BlockItem;
                case RANGED -> s -> s.is(Items.BOW) || s.is(Items.CROSSBOW) || s.is(Items.FISHING_ROD) || s.is(Items.TRIDENT);
                case PREVIOUS -> s -> false;
            };
        }

        /** First hotbar slot whose item matches, searching from the slot after the current one. */
        public static int find(List<ItemStack> hotbar, int current, Predicate<ItemStack> match) {
            for (int i = 1; i <= hotbar.size(); i++) {
                int slot = (current + i) % hotbar.size();
                ItemStack s = hotbar.get(slot);
                if (!s.isEmpty() && match.test(s)) return slot;
            }
            return -1;
        }

        private void tick() {
            Player p = Minecraft.getInstance().player;
            if (p == null) {
                for (KeyMapping k : keys) while (k.consumeClick()) { /* drain */ }
                return;
            }
            Inventory inv = p.getInventory();
            int sel = inv.getSelectedSlot();
            if (sel != lastSeen) {
                if (lastSeen >= 0) previous = lastSeen;
                lastSeen = sel;
            }
            Kind[] kinds = Kind.values();
            for (int k = 0; k < keys.size() && k < kinds.length; k++) {
                while (keys.get(k).consumeClick()) {
                    int target;
                    if (kinds[k] == Kind.PREVIOUS) {
                        target = previous;
                    } else {
                        List<ItemStack> hotbar = new ArrayList<>(9);
                        for (int i = 0; i < 9; i++) hotbar.add(inv.getItem(i));
                        target = find(hotbar, inv.getSelectedSlot(), matcher(kinds[k]));
                    }
                    if (target >= 0 && target < 9 && target != inv.getSelectedSlot()) inv.setSelectedSlot(target);
                }
            }
        }
    }

    /**
     * Sends a message you wrote when a chat line contains one of your triggers (e.g. "gg" when a
     * game ends). Off by default; many servers have rules about automated chat, check them.
     */
    public static final class AutoMessages extends Module {
        private final StringSetting triggers = setting(new StringSetting("triggers", "cobalmoon.setting.automsg.triggers", "Winner|1st Killer|ganador", 200));
        private final StringSetting message = setting(new StringSetting("message", "cobalmoon.setting.automsg.message", "gg", 100));
        private final NumberSetting delay = setting(new NumberSetting("delay", "cobalmoon.setting.automsg.delay", 1, 0, 10, 0.5));
        private final NumberSetting cooldown = setting(new NumberSetting("cooldown", "cobalmoon.setting.automsg.cooldown", 15, 5, 120, 1));
        private int sendInTicks = -1;
        private long lastSent;

        public AutoMessages() {
            super("auto_messages", "cobalmoon.module.auto_messages", "cobalmoon.module.auto_messages.desc", Category.GAMEPLAY);
            on(Events.ChatMessage.class, e -> {
                if (sendInTicks < 0 && matches(e.text(), triggers.get(), message.get())
                        && System.nanoTime() - lastSent > cooldown.get() * 1e9) {
                    sendInTicks = (int) Math.round(delay.get() * 20);
                }
            });
            on(Events.ClientTick.class, e -> {
                if (sendInTicks < 0) return;
                if (sendInTicks-- > 0) return;
                send(message.get());
                lastSent = System.nanoTime();
            });
            on(Events.ServerLeave.class, e -> sendInTicks = -1);
        }

        /** Case-insensitive "contains any trigger"; ignores lines that are our own message (no loops). */
        public static boolean matches(String text, String triggers, String ownMessage) {
            if (text == null || text.isBlank()) return false;
            String t = text.toLowerCase(Locale.ROOT);
            if (!ownMessage.isBlank() && t.contains(ownMessage.toLowerCase(Locale.ROOT)) && t.length() < ownMessage.length() + 40) return false;
            for (String trig : triggers.split("\\|")) {
                String s = trig.trim().toLowerCase(Locale.ROOT);
                if (!s.isEmpty() && t.contains(s)) return true;
            }
            return false;
        }

        private static void send(String msg) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() == null || msg.isBlank()) return;
            if (msg.startsWith("/")) mc.getConnection().sendCommand(msg.substring(1));
            else mc.getConnection().sendChat(msg);
        }
    }
}
