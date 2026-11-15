package dev.cobalmoon.api;

import dev.cobalmoon.api.theme.DefaultTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CobalmoonTest {

    private static CobalmoonApi fake() {
        return new CobalmoonApi() {
            @Override public String clientVersion() { return "0.1.0"; }
            @Override public String minecraftVersion() { return "26.3"; }
            @Override public dev.cobalmoon.api.module.ModuleRegistry modules() { throw new UnsupportedOperationException(); }
            @Override public dev.cobalmoon.api.event.EventBus events() { throw new UnsupportedOperationException(); }
        };
    }

    @AfterEach
    void reset() {
        Cobalmoon.reset();
    }

    @Test
    void apiThrowsBeforeInstall() {
        assertTrue(Cobalmoon.find().isEmpty());
        assertThrows(IllegalStateException.class, Cobalmoon::api);
    }

    @Test
    void installOnce() {
        CobalmoonApi api = fake();
        Cobalmoon.install(api);
        assertSame(api, Cobalmoon.api());
        assertEquals(CobalmoonApi.API_LEVEL, Cobalmoon.api().apiLevel());
        assertThrows(IllegalStateException.class, () -> Cobalmoon.install(fake()));
    }

    @Test
    void defaultTokensMatchOfficialPalette() {
        assertEquals(0xFF10182C, DefaultTokens.NIGHT);
        assertEquals(0xFF7486B8, DefaultTokens.LUNAR);
        assertEquals(0xFF52E0D0, DefaultTokens.ACCENT);
        assertEquals(0xFFE9EDF2, DefaultTokens.STONE);
        assertEquals(DefaultTokens.ACCENT, DefaultTokens.COLORS.get("accent"));
    }
}
