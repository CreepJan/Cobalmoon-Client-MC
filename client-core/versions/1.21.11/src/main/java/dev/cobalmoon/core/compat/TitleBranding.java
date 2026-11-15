/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.compat;

import dev.cobalmoon.api.CobalmoonApi;
import dev.cobalmoon.api.theme.DefaultTokens;
import dev.cobalmoon.core.Branding;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Draws the Cobalmoon logo and version on the title screen (Minecraft 1.21.11 rendering API). */
public final class TitleBranding {
    private static final Identifier LOGO = Identifier.fromNamespaceAndPath("cobalmoon", "textures/gui/logo.png");
    private static final int LOGO_SIZE = 20;

    private TitleBranding() {}

    public static void register(CobalmoonApi api) {
        String line = Branding.titleScreenLine(api.clientVersion(), api.minecraftVersion());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof TitleScreen)) return;
            ScreenEvents.afterRender(screen).register((s, graphics, mouseX, mouseY, delta) -> {
                var font = Minecraft.getInstance().font;
                int x = 6;
                int y = 6;
                graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO, x, y, 0, 0, LOGO_SIZE, LOGO_SIZE, LOGO_SIZE, LOGO_SIZE);
                graphics.drawString(font, Branding.NAME, x + LOGO_SIZE + 6, y + 1, DefaultTokens.STONE, true);
                graphics.drawString(font, line.substring(Branding.NAME.length()).trim(), x + LOGO_SIZE + 6, y + 11, DefaultTokens.ACCENT, true);
            });
        });
    }
}
