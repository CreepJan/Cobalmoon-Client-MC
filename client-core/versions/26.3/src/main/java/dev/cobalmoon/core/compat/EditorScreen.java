/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.compat;

import dev.cobalmoon.core.editor.HudEditor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Thin 26.x screen that forwards everything to the version-independent {@link HudEditor}. */
public final class EditorScreen extends Screen {
    private static final int SHIFT = 1;
    private final HudEditor editor;

    public EditorScreen(HudEditor editor) {
        super(Component.translatable("cobalmoon.editor.title"));
        this.editor = editor;
    }

    public HudEditor editor() { return editor; }

    @Override protected void init() { editor.init(width, height); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        editor.render(new VersionCanvas(g), mouseX, mouseY);
    }

    /** No blur or dark overlay: the game stays visible so the HUD can be placed in context. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {}

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        return editor.mouseClicked(e.x(), e.y(), e.button(), (e.modifiers() & SHIFT) != 0);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        return editor.mouseReleased(e.x(), e.y(), e.button());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        return editor.mouseDragged(e.x(), e.y());
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        return editor.mouseScrolled(x, y, v);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (editor.keyPressed(e.key(), (e.modifiers() & SHIFT) != 0)) return true;
        return super.keyPressed(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        return editor.charTyped(e.codepointAsString()) || super.charTyped(e);
    }

    @Override public void removed() { editor.close(); }

    @Override public boolean isPauseScreen() { return false; }
}
