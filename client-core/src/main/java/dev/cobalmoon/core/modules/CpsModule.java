/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.modules;

import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.TextHudElement;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.BooleanSetting;
import dev.cobalmoon.core.input.Clicks;

import java.util.List;

/** Clicks per second (left, and optionally right). Counts only clicks that reach the game. */
public final class CpsModule extends Module {
    private final BooleanSetting showRight = setting(new BooleanSetting("show_right", "cobalmoon.setting.cps.show_right", true));
    private final BooleanSetting showLabel = setting(new BooleanSetting("show_label", "cobalmoon.setting.show_label", true));

    public CpsModule() {
        super("cps", "cobalmoon.module.cps", "cobalmoon.module.cps.desc", Category.HUD);
        hud(new TextHudElement("cps", "cobalmoon.module.cps", Anchor.TOP_LEFT, 4, 22) {
            @Override
            protected List<String> lines(boolean preview) {
                String value = showRight.get()
                        ? Clicks.LEFT.cps() + " | " + Clicks.RIGHT.cps()
                        : Integer.toString(Clicks.LEFT.cps());
                return List.of(showLabel.get() ? labeled("CPS", value) : value);
            }
        });
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }
}
