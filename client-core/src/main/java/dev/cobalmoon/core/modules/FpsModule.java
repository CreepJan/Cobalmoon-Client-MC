/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.modules;

import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.TextHudElement;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.BooleanSetting;
import net.minecraft.client.Minecraft;

import java.util.List;

public final class FpsModule extends Module {
    private final BooleanSetting showLabel = setting(new BooleanSetting("show_label", "cobalmoon.setting.show_label", true));

    public FpsModule() {
        super("fps", "cobalmoon.module.fps", "cobalmoon.module.fps.desc", Category.HUD);
        hud(new TextHudElement("fps", "cobalmoon.module.fps", Anchor.TOP_LEFT, 4, 4) {
            @Override
            protected List<String> lines(boolean preview) {
                String fps = Integer.toString(Minecraft.getInstance().getFps());
                return List.of(showLabel.get() ? labeled("FPS", fps) : fps);
            }
        });
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }
}
