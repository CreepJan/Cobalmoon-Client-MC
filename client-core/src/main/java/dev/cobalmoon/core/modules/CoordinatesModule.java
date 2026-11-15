/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.modules;

import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.TextHudElement;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.BooleanSetting;
import dev.cobalmoon.api.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CoordinatesModule extends Module {
    private static final String[] DIRECTIONS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    private final NumberSetting decimals = setting(new NumberSetting("decimals", "cobalmoon.setting.coords.decimals", 0, 0, 3, 1));
    private final BooleanSetting showDirection = setting(new BooleanSetting("show_direction", "cobalmoon.setting.coords.show_direction", true));
    private final BooleanSetting oneLine = setting(new BooleanSetting("one_line", "cobalmoon.setting.coords.one_line", false));

    public CoordinatesModule() {
        super("coordinates", "cobalmoon.module.coordinates", "cobalmoon.module.coordinates.desc", Category.HUD);
        hud(new TextHudElement("coordinates", "cobalmoon.module.coordinates", Anchor.TOP_LEFT, 4, 40) {
            @Override
            protected List<String> lines(boolean preview) {
                Player p = Minecraft.getInstance().player;
                double x = p != null ? p.getX() : 128.5, y = p != null ? p.getY() : 64, z = p != null ? p.getZ() : -256.5;
                float yaw = p != null ? p.getYRot() : 180f;
                return CoordinatesModule.this.lines(x, y, z, yaw);
            }
        });
    }

    /** Compass direction for a Minecraft yaw (0 = south, 90 = west). */
    public static String direction(float yaw) {
        int i = Math.floorMod(Math.round(yaw / 45f), 8);
        return DIRECTIONS[i];
    }

    List<String> lines(double x, double y, double z, float yaw) {
        String fmt = "%." + decimals.intValue() + "f";
        String fx = String.format(Locale.ROOT, fmt, x), fy = String.format(Locale.ROOT, fmt, y), fz = String.format(Locale.ROOT, fmt, z);
        List<String> out = new ArrayList<>();
        if (oneLine.get()) {
            out.add("XYZ" + TextHudElement.LABEL_SEPARATOR + fx + " " + fy + " " + fz);
        } else {
            out.add("X" + TextHudElement.LABEL_SEPARATOR + fx);
            out.add("Y" + TextHudElement.LABEL_SEPARATOR + fy);
            out.add("Z" + TextHudElement.LABEL_SEPARATOR + fz);
        }
        if (showDirection.get()) out.add("Dir" + TextHudElement.LABEL_SEPARATOR + direction(yaw));
        return out;
    }
}
