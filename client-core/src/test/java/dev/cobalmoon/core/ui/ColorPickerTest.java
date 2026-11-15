package dev.cobalmoon.core.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColorPickerTest {

    @Test
    void primaryColours() {
        assertEquals(0xFFFF0000, ColorPicker.fromHsv(0f, 1f, 1f, 255));
        assertEquals(0xFF00FF00, ColorPicker.fromHsv(1f / 3, 1f, 1f, 255));
        assertEquals(0xFF0000FF, ColorPicker.fromHsv(2f / 3, 1f, 1f, 255));
        assertEquals(0xFF00FFFF, ColorPicker.fromHsv(0.5f, 1f, 1f, 255));
        assertEquals(0x80FFFFFF, ColorPicker.fromHsv(0.3f, 0f, 1f, 0x80), "no saturation = white, alpha kept");
        assertEquals(0xFF000000, ColorPicker.fromHsv(0.7f, 1f, 0f, 255), "no brightness = black");
    }

    @Test
    void roundTripsThePalette() {
        for (int c : Theme.SWATCHES) {
            float[] hsv = ColorPicker.toHsv(c);
            int back = ColorPicker.fromHsv(hsv[0], hsv[1], hsv[2], c >>> 24);
            for (int shift = 0; shift <= 16; shift += 8) {
                assertTrue(Math.abs(((c >> shift) & 0xFF) - ((back >> shift) & 0xFF)) <= 1,
                        String.format("%08X -> %08X", c, back));
            }
        }
    }

    @Test
    void hueWrapsAround() {
        assertEquals(ColorPicker.fromHsv(0.1f, 1, 1, 255), ColorPicker.fromHsv(1.1f, 1, 1, 255));
        assertEquals(ColorPicker.fromHsv(0.9f, 1, 1, 255), ColorPicker.fromHsv(-0.1f, 1, 1, 255));
    }
}
