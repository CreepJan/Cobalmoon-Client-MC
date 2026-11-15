package dev.cobalmoon.core.ui;

import dev.cobalmoon.api.theme.DefaultTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ThemeTest {
    @AfterEach
    void reset() {
        Theme.reset();
    }

    @Test
    void contrastMatchesTheTokenPipeline() {
        // Same values as design-tokens/scripts (docs/design-tokens.md).
        assertEquals(15.02, Theme.contrast(DefaultTokens.STONE, DefaultTokens.NIGHT), 0.01);
        assertEquals(4.92, Theme.contrast(DefaultTokens.LUNAR, DefaultTokens.NIGHT), 0.01);
        assertEquals(21.0, Theme.contrast(0xFF000000, 0xFFFFFFFF), 1e-9);
    }

    @Test
    void defaultThemeHasNoWarnings() {
        assertTrue(Theme.contrastWarnings().isEmpty(), Theme.contrastWarnings().toString());
    }

    @Test
    void unreadableThemeIsFlagged() {
        Theme.set("stone", 0xFF20283C); // dark text on dark panels
        var w = Theme.contrastWarnings();
        assertFalse(w.isEmpty());
        assertEquals("stone", w.get(0).fg());
        assertTrue(w.get(0).ratio() < 4.5);
    }

    @Test
    void applyAcceptsOnlyKnownKeysAndValidColours() {
        int n = Theme.apply(Map.of("accent", "#FF8800", "nonsense", "#FFFFFF", "lunar", "blue"));
        assertEquals(1, n);
        assertEquals(0xFFFF8800, Theme.accent());
        assertEquals(DefaultTokens.LUNAR, Theme.muted(), "invalid value ignored");
        Theme.set("accent", 0x00123456);
        assertEquals(0xFF123456, Theme.accent(), "theme colours are forced opaque");
        Theme.reset();
        assertEquals(DefaultTokens.ACCENT, Theme.accent());
    }
}
