/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.compat;

import dev.cobalmoon.core.editor.HudEditor;
import dev.cobalmoon.core.render.HudLayer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Version glue for Minecraft 26.x. Same signatures exist in every versions/<mc> source set. */
public final class Platform {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("cobalmoon", "main"));

    private Platform() {}

    public static void registerHud(HudLayer.Draw draw) {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("cobalmoon", "hud"),
                (graphics, delta) -> draw.draw(new VersionCanvas(graphics)));
    }

    public static void registerCrosshair(HudLayer.Crosshair crosshair) {
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (graphics, delta) -> {
            if (!crosshair.draw(new VersionCanvas(graphics))) vanilla.extractRenderState(graphics, delta);
        });
    }

    public static KeyMapping registerKey(String name, int glfwKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, glfwKey, CATEGORY));
    }

    public static boolean isScreenOpen() {
        return Minecraft.getInstance().gui.screen() != null;
    }

    public static boolean isEditorOpen() {
        return Minecraft.getInstance().gui.screen() instanceof EditorScreen;
    }

    public static boolean firstPerson() {
        return Minecraft.getInstance().options.getCameraType().isFirstPerson();
    }

    /** The open editor, or null. */
    public static HudEditor currentEditor() {
        return Minecraft.getInstance().gui.screen() instanceof EditorScreen s ? s.editor() : null;
    }

    public static void closeScreen() {
        Minecraft.getInstance().gui.setScreen(null);
    }

    public static void openEditor(HudEditor editor) {
        Minecraft.getInstance().gui.setScreen(new EditorScreen(editor));
    }
}
