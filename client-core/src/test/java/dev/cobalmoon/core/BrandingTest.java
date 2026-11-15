package dev.cobalmoon.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrandingTest {

    @Test
    void prefixesOriginalTitle() {
        assertEquals("Cobalmoon Client 0.1.0 · Minecraft 26.3 - Multiplayer",
                Branding.windowTitle("Minecraft 26.3 - Multiplayer", "0.1.0"));
    }

    @Test
    void doesNotDoublePrefix() {
        String once = Branding.windowTitle("Minecraft 26.3", "0.1.0");
        assertEquals(once, Branding.windowTitle(once, "0.1.0"));
    }

    @Test
    void blankOriginal() {
        assertEquals("Cobalmoon Client 0.1.0", Branding.windowTitle("  ", "0.1.0"));
    }

    @Test
    void brandNeverStartsWithMinecraft() {
        assertTrue(Branding.titleScreenLine("0.1.0", "1.21.11").startsWith("Cobalmoon Client"));
        assertTrue(Branding.titleScreenLine("0.1.0", "1.21.11").contains("para Minecraft 1.21.11"));
    }
}
