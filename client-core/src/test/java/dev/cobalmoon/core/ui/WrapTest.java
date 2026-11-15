package dev.cobalmoon.core.ui;

import dev.cobalmoon.core.render.GuiCanvas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WrapTest {
    /** 6 px per character, like Minecraft's font for most glyphs. */
    static final class Fixed implements GuiCanvas {
        @Override public int textWidth(String t) { return t.length() * 6; }
        @Override public int fontHeight() { return 8; }
        @Override public void text(String t, int x, int y, int c, boolean s) {}
        @Override public void fill(int a, int b, int c, int d, int e) {}
        @Override public int screenWidth() { return 480; }
        @Override public int screenHeight() { return 270; }
        @Override public void push(float x, float y, float s) {}
        @Override public void pop() {}
        @Override public float setAlpha(float a) { return 1; }
        @Override public float alpha() { return 1; }
        @Override public void clip(int a, int b, int c, int d) {}
        @Override public void endClip() {}
        @Override public void nextLayer() {}
    }

    @Test
    void wrapsOnWordsAndKeepsEverything() {
        List<String> lines = Widgets.wrap(new Fixed(), "Selecciona un elemento o un módulo.", 60);
        // 60 px = 10 characters per line
        assertEquals(List.of("Selecciona", "un", "elemento o", "un módulo."), lines);
        for (String l : lines) assertTrue(l.length() <= 10, l);
        assertEquals("Selecciona un elemento o un módulo.", String.join(" ", lines));
    }

    @Test
    void splitsWordsLongerThanTheLine() {
        List<String> lines = Widgets.wrap(new Fixed(), "abcdefghij", 24);
        assertEquals(List.of("abcd", "efgh", "ij"), lines);
    }

    @Test
    void shortTextIsOneLine() {
        assertEquals(List.of("FPS"), Widgets.wrap(new Fixed(), "FPS", 100));
    }
}
