package dev.cobalmoon.core;

import dev.cobalmoon.core.training.TrainingMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrainingMetricsTest {
    static final long S = 1_000_000_000L;

    @Test
    void cpsAverageBestAndConsistency() {
        var c = new TrainingMetrics.Cps();
        // second 0: 10 clicks, second 1: 6 clicks
        for (int i = 0; i < 10; i++) c.click(i * S / 10);
        for (int i = 0; i < 6; i++) c.click(S + i * S / 6);
        assertEquals(16, c.count());
        assertEquals(8.0, c.average(2), 1e-9);
        assertEquals(10, c.best());
        assertEquals(2.0, c.consistency(0, 2), 1e-9, "per-second counts 10 and 6 → σ = 2");
        assertEquals(0, new TrainingMetrics.Cps().best());
    }

    @Test
    void aimWhileStrafing() {
        var a = new TrainingMetrics.Aim();
        for (int i = 0; i < 10; i++) a.tick(true, false);   // standing still, on target
        for (int i = 0; i < 10; i++) a.tick(i < 6, true);    // strafing, 6/10 on target
        assertEquals(16.0 / 20, a.overall(), 1e-9);
        assertEquals(0.6, a.whileStrafing(), 1e-9);
        assertEquals(0.5, a.strafeShare(), 1e-9);
        assertTrue(Double.isNaN(new TrainingMetrics.Aim().whileStrafing()), "no strafing → nothing measured, not 0%");
    }

    @Test
    void wTapNeedsASprintResetBetweenHits() {
        var w = new TrainingMetrics.WTap();
        w.tick(true);
        w.hit(true);                  // first hit while sprinting: good
        w.tick(true);
        w.hit(true);                  // no reset since last hit: bad
        w.tick(false);
        w.tick(true);
        w.hit(true);                  // released W then sprinted again: good
        w.tick(false);
        w.hit(false);                 // hit while not sprinting: bad
        assertEquals(4, w.hits());
        assertEquals(0.5, w.ratio(), 1e-9);
    }

    @Test
    void bridgingCountsPlacedBlocksDistanceAndFalls() {
        var b = new TrainingMetrics.Bridge(0, 64, 0);
        b.tick(64, 0, 64, 0);
        b.tick(60, 3, 64, 0);   // placed 4
        b.tick(60, 3, 60, 0);   // fell (1)
        b.tick(60, 3, 59, 0);   // still down: same fall
        b.tick(64, 3, 64, 0);   // picked blocks back up: not "placed"
        b.tick(58, 4, 64, 0);   // placed 6 more
        assertEquals(10, b.placed());
        assertEquals(1, b.falls());
        assertEquals(4.0, b.distance(), 1e-9);
        assertEquals(2.0, b.blocksPerSecond(5), 1e-9);
    }
}
