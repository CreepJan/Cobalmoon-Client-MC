package dev.cobalmoon.core;

import dev.cobalmoon.core.fight.FightHistory;
import dev.cobalmoon.core.fight.FightRecord;
import dev.cobalmoon.core.fight.FightStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FightTest {
    static final long MS = 1_000_000L;
    @TempDir Path dir;

    /** A scripted 4-second duel. */
    static FightRecord duel(long ts, int extraMisses) {
        FightStats f = new FightStats(0, "Alex");
        for (int i = 0; i < 6 + extraMisses; i++) f.swing(i * 100 * MS);
        f.attack(0); f.hit(50 * MS, 2.9, false, 3f, 0, 2);
        f.attack(500 * MS); f.hit(550 * MS, 2.5, true, 4.5f, 0.5, 2);
        f.attack(1000 * MS); f.hit(1050 * MS, 2.7, false, 3f, 1, 2);
        f.hurt(1200 * MS, 4f, 1, 2);                    // combo broken; reaction timer starts
        f.attack(1450 * MS); f.hit(1500 * MS, 3.0, true, 4.5f, 1.2, 2); // reacted in 250 ms
        f.hurt(2000 * MS, 3f, 1, 2);
        f.attack(5000 * MS);                            // 3 s later: not a reaction
        for (int i = 0; i < 40; i++) f.sample(i * 100 * MS, i * 0.1, 0, 90f, i * 0.1 + 2, 0);
        return f.finish(4000 * MS, FightRecord.Outcome.WIN, ts);
    }

    @Test
    void statsOfAScriptedDuel() {
        FightRecord r = duel(1, 0);
        assertEquals(4000, r.durationMs());
        assertEquals("Alex", r.opponent());
        assertEquals(6, r.swings());
        assertEquals(5, r.attacks());
        assertEquals(4, r.hits());
        assertEquals(2, r.crits());
        assertEquals(3, r.maxCombo(), "three hits before being hit");
        assertEquals((2.9 + 2.5 + 2.7 + 3.0) / 4, r.avgReach(), 1e-9);
        assertEquals(3.0, r.maxReach(), 1e-9);
        assertEquals(15f, r.damageDealt(), 1e-6);
        assertEquals(7f, r.damageTaken(), 1e-6);
        assertEquals(250, r.avgReactionMs(), "only reactions under 1.5 s count");
        assertEquals(4.0 / 6, r.accuracy(), 1e-9);
        // No clicks seen (e.g. attack on another key): accuracy falls back to attacks.
        FightStats noClicks = new FightStats(0, "B");
        noClicks.attack(0);
        noClicks.hit(1, 2.5, false, 1, 0, 0);
        noClicks.attack(2);
        assertEquals(0.5, noClicks.finish(10, FightRecord.Outcome.UNKNOWN, 0).accuracy(), 1e-9);
        assertEquals(40, r.track().size());
        assertEquals(6, r.marks().size());
        assertTrue(r.hasTrack());
    }

    @Test
    void historyComparesWithEarlierFightsAndPersists() throws Exception {
        Path file = dir.resolve("fights.json");
        FightHistory h = new FightHistory(file);
        FightRecord first = duel(1, 6);   // 4/12 accuracy
        h.add(first);
        assertTrue(Double.isNaN(h.delta(first, FightRecord::accuracy)), "no history yet");
        FightRecord second = duel(2, 0);  // 4/6 accuracy
        h.add(second);
        assertEquals(4.0 / 6 - 4.0 / 12, h.delta(second, FightRecord::accuracy), 1e-9);

        FightHistory reloaded = new FightHistory(file);
        assertEquals(2, reloaded.records().size());
        assertEquals(second.maxCombo(), reloaded.latest().maxCombo());
        assertEquals(40, reloaded.latest().track().size(), "replay track survives a restart");
        assertEquals(FightRecord.Outcome.WIN, reloaded.latest().outcome());
    }

    @Test
    void historyIsCappedAndOldTracksAreDropped() {
        FightHistory h = new FightHistory(dir.resolve("f.json"));
        for (int i = 0; i < FightHistory.KEEP + 5; i++) h.add(duel(i, 0));
        assertEquals(FightHistory.KEEP, h.records().size());
        assertEquals(FightHistory.KEEP_TRACKS, h.replays().size());
        assertEquals(5, h.records().get(0).timestamp(), "oldest fights dropped first");
    }

    @Test
    void brokenHistoryFileIsSetAside() throws Exception {
        Path file = dir.resolve("fights.json");
        Files.writeString(file, "[{not json");
        FightHistory h = new FightHistory(file);
        assertTrue(h.records().isEmpty());
        assertTrue(Files.exists(dir.resolve("fights.json.bak")));
    }
}
