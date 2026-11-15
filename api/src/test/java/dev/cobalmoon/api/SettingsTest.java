package dev.cobalmoon.api;

import dev.cobalmoon.api.setting.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    enum Style { CROSS, DOT }

    @Test
    void numberClampsAndSnaps() {
        NumberSetting n = new NumberSetting("size", "Size", 4, 1, 10, 0.5);
        n.set(99.0);
        assertEquals(10.0, n.get());
        n.set(-3.0);
        assertEquals(1.0, n.get());
        n.set(3.26);
        assertEquals(3.5, n.get());
        n.set(Double.NaN);
        assertEquals(4.0, n.get());
        n.setFraction(0.5);
        assertEquals(5.5, n.get());
    }

    @Test
    void numberRejectsBadRange() {
        assertThrows(IllegalArgumentException.class, () -> new NumberSetting("a", "A", 5, 10, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new NumberSetting("a", "A", 50, 0, 10, 1));
    }

    @Test
    void invalidJsonFallsBackToDefault() {
        BooleanSetting b = new BooleanSetting("on", "On", true);
        b.fromJson("yes");
        assertTrue(b.get());
        NumberSetting n = new NumberSetting("n", "N", 2, 0, 5, 1);
        n.fromJson("nope");
        assertEquals(2.0, n.get());
        n.fromJson(4);
        assertEquals(4.0, n.get());
        EnumSetting<Style> e = new EnumSetting<>("style", "Style", Style.CROSS);
        e.fromJson("dot");
        assertEquals(Style.DOT, e.get());
        e.fromJson("TRIANGLE");
        assertEquals(Style.CROSS, e.get());
    }

    @Test
    void colorHexRoundtrip() {
        ColorSetting c = new ColorSetting("color", "Color", 0xFF52E0D0);
        assertEquals("#FF52E0D0", c.toJson());
        c.fromJson("#7486b8");
        assertEquals(0xFF7486B8, c.get());
        c.fromJson("#80112233");
        assertEquals(0x80112233, c.get());
        c.fromJson("red");
        assertEquals(0xFF52E0D0, c.get());
        c.setAlpha(0x40);
        assertEquals(0x4052E0D0, c.get());
        assertNull(ColorSetting.parseHex("#12345"));
    }

    @Test
    void enumCyclesBothWays() {
        EnumSetting<Style> e = new EnumSetting<>("style", "Style", Style.CROSS);
        e.cycle(1);
        assertEquals(Style.DOT, e.get());
        e.cycle(1);
        assertEquals(Style.CROSS, e.get());
        e.cycle(-1);
        assertEquals(Style.DOT, e.get());
    }

    @Test
    void listenersOnlyFireOnRealChanges() {
        BooleanSetting b = new BooleanSetting("on", "On", false);
        List<Boolean> seen = new ArrayList<>();
        b.onChange(seen::add);
        b.set(false);
        b.set(true);
        b.set(true);
        b.toggle();
        assertEquals(List.of(true, false), seen);
    }

    @Test
    void keysAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new BooleanSetting("Bad Key", "x", true));
    }
}
