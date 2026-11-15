package dev.cobalmoon.core;

import dev.cobalmoon.core.combat.CombatTracker;
import dev.cobalmoon.core.modules.CombatHudModules.ComboCounter;
import dev.cobalmoon.core.modules.GameplayModules.AutoMessages;
import dev.cobalmoon.core.modules.SafeServerModule;
import dev.cobalmoon.core.modules.StatusHudModules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PvpLogicTest {
    static final long S = 1_000_000_000L;

    @Test
    void comboCountsConsecutiveHitsOnOneTarget() {
        ComboCounter c = new ComboCounter();
        c.hit(7, 0, 3 * S);
        c.hit(7, S, 3 * S);
        c.hit(7, 2 * S, 3 * S);
        assertEquals(3, c.value(2 * S, 3 * S));
        assertEquals(0, c.value(6 * S, 3 * S), "expires after the timeout");
        c.hit(9, 2 * S + 1, 3 * S);
        assertEquals(1, c.value(2 * S + 1, 3 * S), "new target starts a new combo");
        c.reset();
        assertEquals(0, c.value(2 * S + 1, 3 * S), "taking damage resets");
    }

    @Test
    void reachIsMeasuredToTheHitbox() {
        // Eye at x=0, box from x=2.5 to 3.1: distance 2.5.
        assertEquals(2.5, CombatTracker.distanceToBox(0, 1.62, 0, 2.5, 0, -0.3, 3.1, 1.8, 0.3), 1e-9);
        // Diagonal corner.
        assertEquals(Math.sqrt(2), CombatTracker.distanceToBox(0, 0, 0, 1, 1, -1, 2, 2, 1), 1e-9);
        // Inside the box.
        assertEquals(0, CombatTracker.distanceToBox(0, 0, 0, -1, -1, -1, 1, 1, 1), 1e-9);
    }

    @Test
    void effectFormatting() {
        assertEquals("5s", StatusHudModules.formatTicks(100));
        assertEquals("1:05", StatusHudModules.formatTicks(65 * 20));
        assertEquals("1s", StatusHudModules.formatTicks(3), "rounds up so it never shows 0s while active");
        assertEquals("∞", StatusHudModules.formatTicks(-1));
        assertEquals("", StatusHudModules.roman(0));
        assertEquals(" II", StatusHudModules.roman(1));
        assertEquals(" 12", StatusHudModules.roman(11));
    }

    @Test
    void durabilityColourGoesFromGreenToRed() {
        int full = StatusHudModules.durabilityColor(1f), empty = StatusHudModules.durabilityColor(0f);
        assertEquals(0, (full >> 16) & 0xFF, "no red when full");
        assertEquals(255, (full >> 8) & 0xFF);
        assertEquals(255, (empty >> 16) & 0xFF);
        assertEquals(0, (empty >> 8) & 0xFF, "no green when broken");
    }

    @Test
    void autoMessageTriggers() {
        assertTrue(AutoMessages.matches("§6Winner: Steve", "Winner|1st Killer", "gg"));
        assertTrue(AutoMessages.matches("1ST KILLER - Alex", "Winner|1st Killer", "gg"));
        assertFalse(AutoMessages.matches("hello", "Winner|1st Killer", "gg"));
        assertFalse(AutoMessages.matches("anything", "", "gg"), "no triggers = never");
        assertFalse(AutoMessages.matches("<Steve> gg Winner", "Winner", "gg"), "our own short message never re-triggers");
    }

    @Test
    void safeServerGuideMatching() {
        assertEquals("play.example.net", SafeServerModule.hostOf("Play.Example.NET:25565"));
        assertEquals("example.net", SafeServerModule.hostOf("example.net."));
        assertEquals("::1", SafeServerModule.hostOf("[::1]:25565"));
        assertNull(SafeServerModule.hostOf(" "));
        assertTrue(SafeServerModule.hostMatches("mc.example.net", "example.net"));
        assertTrue(SafeServerModule.hostMatches("example.net", "example.net"));
        assertFalse(SafeServerModule.hostMatches("notexample.net", "example.net"));

        List<SafeServerModule.Entry> guide = SafeServerModule.parse("""
                {"servers":[{"name":"Example","hosts":["example.net"],
                  "restricted":{"auto_messages":"Automated chat","zoom":""},"source":"https://example.net/rules"}]}""");
        assertEquals(1, guide.size());
        SafeServerModule.Entry e = SafeServerModule.find(guide, "mc.example.net");
        assertNotNull(e);
        assertEquals(List.of("zoom"), SafeServerModule.restrictedEnabled(e, Set.of("zoom", "fps")));
        assertNull(SafeServerModule.find(guide, "other.org"));
        assertTrue(SafeServerModule.parse("{not json").isEmpty(), "a broken guide is ignored, not fatal");
        assertTrue(SafeServerModule.parse("{\"servers\":[{\"name\":\"x\"}]}").isEmpty(), "incomplete entries are skipped");
        assertEquals(Map.of("auto_messages", "Automated chat", "zoom", ""), e.restricted());
    }
}
