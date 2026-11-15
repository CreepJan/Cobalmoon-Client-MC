package dev.cobalmoon.core;

import dev.cobalmoon.api.hud.HudCanvas;
import dev.cobalmoon.core.editor.Snapping;
import dev.cobalmoon.core.input.ClickTracker;
import dev.cobalmoon.core.modules.CoordinatesModule;
import dev.cobalmoon.core.modules.CrosshairModule;
import dev.cobalmoon.core.modules.KeystrokesModule;
import dev.cobalmoon.core.render.HudRenderer.Bounds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogicTest {
    static final long SEC = 1_000_000_000L;

    @Test
    void cpsCountsLastSecondOnly() {
        ClickTracker t = new ClickTracker(64);
        long now = 10 * SEC;
        for (int i = 0; i < 5; i++) t.record(now - 2 * SEC + i);       // too old
        for (int i = 0; i < 12; i++) t.record(now - SEC / 2 + i * 1000); // recent
        assertEquals(12, t.cps(now));
        assertEquals(0, t.cps(now + 2 * SEC));
    }

    @Test
    void cpsRingBufferWraps() {
        ClickTracker t = new ClickTracker(8);
        long now = 5 * SEC;
        for (int i = 0; i < 20; i++) t.record(now - 100 + i);
        assertEquals(8, t.cps(now + 50), "capped at capacity");
    }

    @Test
    void snapsToScreenEdgesAndCentre() {
        Snapping.Result r = Snapping.snap(3, 50, 40, 10, 480, 270, List.of(), 4);
        assertEquals(0, r.x());
        assertEquals(50, r.y());
        assertEquals(1, r.guides().size());
        assertTrue(r.guides().get(0).vertical());

        // centre of element near centre of screen
        Snapping.Result c = Snapping.snap(219, 130, 40, 10, 480, 270, List.of(), 4);
        assertEquals(220, c.x());
        assertEquals(130, c.y());
    }

    @Test
    void snapsToOtherElements() {
        Bounds other = new Bounds(100, 100, 50, 20);
        Snapping.Result r = Snapping.snap(152, 122, 30, 10, 480, 270, List.of(other), 4);
        assertEquals(150, r.x(), "left edge to other's right edge");
        assertEquals(120, r.y(), "top edge to other's bottom edge");
        assertEquals(2, r.guides().size());
    }

    @Test
    void noSnapOutsideThreshold() {
        Snapping.Result r = Snapping.snap(60, 60, 30, 10, 480, 270, List.of(), 4);
        assertEquals(60, r.x());
        assertEquals(60, r.y());
        assertTrue(r.guides().isEmpty());
    }

    @Test
    void compassDirections() {
        assertEquals("S", CoordinatesModule.direction(0));
        assertEquals("W", CoordinatesModule.direction(90));
        assertEquals("N", CoordinatesModule.direction(180));
        assertEquals("N", CoordinatesModule.direction(-180));
        assertEquals("E", CoordinatesModule.direction(-90));
        assertEquals("SE", CoordinatesModule.direction(-45));
    }

    @Test
    void blendEndpoints() {
        assertEquals(0xFF000000, KeystrokesModule.blend(0xFF000000, 0xFFFFFFFF, 0));
        assertEquals(0xFFFFFFFF, KeystrokesModule.blend(0xFF000000, 0xFFFFFFFF, 1));
        assertEquals(0x80808080, KeystrokesModule.blend(0x00000000, 0xFFFFFFFF, 0.5f) & 0xFFFFFFFF, "rounds .5 up");
    }

    /** Records fills so the crosshair geometry can be checked without Minecraft. */
    static final class Recorder implements HudCanvas {
        final List<int[]> fills = new ArrayList<>();
        @Override public int textWidth(String text) { return text.length() * 6; }
        @Override public int fontHeight() { return 8; }
        @Override public void text(String text, int x, int y, int argb, boolean shadow) {}
        @Override public void fill(int x1, int y1, int x2, int y2, int argb) { fills.add(new int[]{x1, y1, x2, y2, argb}); }
        @Override public int screenWidth() { return 480; }
        @Override public int screenHeight() { return 270; }
    }

    @Test
    void crosshairIsSymmetricAndUsesColour() {
        CrosshairModule m = new CrosshairModule();
        Recorder r = new Recorder();
        m.draw(r, 240, 135);
        // default: cross with outline -> 4 outline rects + 4 arms
        assertEquals(8, r.fills.size());
        List<int[]> arms = r.fills.subList(4, 8);
        for (int[] a : arms) assertEquals(dev.cobalmoon.api.theme.DefaultTokens.ACCENT, a[4]);
        int[] left = arms.get(0), right = arms.get(1);
        assertEquals(240 - left[2], right[0] - 241, "gap is equal on both sides of the 1px centre");
        assertEquals(left[2] - left[0], right[2] - right[0], "arms have equal length");
    }
}
