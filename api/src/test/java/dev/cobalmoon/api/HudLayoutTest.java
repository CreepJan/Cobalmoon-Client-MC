package dev.cobalmoon.api;

import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.HudLayout;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HudLayoutTest {
    static final int W = 480, H = 270;

    @Test
    void positionsFollowAnchor() {
        HudLayout tl = new HudLayout(Anchor.TOP_LEFT, 5, 7);
        assertEquals(5, tl.x(W, 40));
        assertEquals(7, tl.y(H, 10));

        HudLayout br = new HudLayout(Anchor.BOTTOM_RIGHT, 5, 7);
        assertEquals(W - 40 - 5, br.x(W, 40));
        assertEquals(H - 10 - 7, br.y(H, 10));

        HudLayout c = new HudLayout(Anchor.CENTER, 0, 0);
        assertEquals((W - 40) / 2, c.x(W, 40));
    }

    @Test
    void positionIsClampedOnScreen() {
        HudLayout l = new HudLayout(Anchor.TOP_LEFT, 9999, -50);
        assertEquals(W - 40, l.x(W, 40));
        assertEquals(0, l.y(H, 10));
    }

    @Test
    void moveToRoundTripsForEveryRegion() {
        int[][] points = {{3, 4}, {220, 4}, {430, 4}, {3, 130}, {220, 130}, {430, 130}, {3, 255}, {220, 255}, {430, 255}};
        for (int[] p : points) {
            HudLayout l = new HudLayout(Anchor.TOP_LEFT, 0, 0);
            l.moveTo(p[0], p[1], 40, 10, W, H);
            assertEquals(p[0], l.x(W, 40), "x for " + l.anchor);
            assertEquals(p[1], l.y(H, 10), "y for " + l.anchor);
        }
    }

    @Test
    void moveToPicksAnchorByThird() {
        HudLayout l = new HudLayout(Anchor.TOP_LEFT, 0, 0);
        l.moveTo(W - 45, H - 15, 40, 10, W, H);
        assertEquals(Anchor.BOTTOM_RIGHT, l.anchor);
        assertEquals(5, l.offsetX);
        assertEquals(5, l.offsetY);
    }

    @Test
    void keepsRelativePlaceOnResize() {
        HudLayout l = new HudLayout(Anchor.TOP_LEFT, 0, 0);
        l.moveTo(W - 45, 4, 40, 10, W, H);
        assertEquals(Anchor.TOP_RIGHT, l.anchor);
        // Bigger window: still 5 px from the right edge.
        assertEquals(960 - 40 - 5, l.x(960, 40));
    }

    @Test
    void jsonRoundtripAndValidation() {
        HudLayout a = new HudLayout(Anchor.CENTER_RIGHT, 3, -2);
        a.setScale(1.37f);
        a.setOpacity(0.5f);
        a.background = false;
        a.textColor = 0xFF112233;
        Map<String, Object> json = a.toJson();

        HudLayout b = new HudLayout(Anchor.TOP_LEFT, 0, 0);
        b.fromJson(json);
        assertEquals(json, b.toJson());
        assertEquals(1.35f, b.scale, 1e-6);

        b.fromJson(Map.of("scale", 100, "anchor", "NOWHERE", "textColor", "blue"));
        assertEquals(HudLayout.MAX_SCALE, b.scale);
        assertEquals(Anchor.CENTER_RIGHT, b.anchor);
        assertEquals(0xFF112233, b.textColor);
    }
}
