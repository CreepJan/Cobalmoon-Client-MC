/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.training;

import java.util.ArrayList;
import java.util.List;

/** Pure measurement logic of the training exercises (no Minecraft types, unit-tested). */
public final class TrainingMetrics {
    private TrainingMetrics() {}

    /** Clicks per second test. */
    public static final class Cps {
        private final List<Long> clicks = new ArrayList<>();

        public void click(long nanos) { clicks.add(nanos); }

        public int count() { return clicks.size(); }

        public double average(double seconds) {
            return seconds <= 0 ? 0 : clicks.size() / seconds;
        }

        /** Most clicks inside any 1-second window. */
        public int best() {
            int best = 0, j = 0;
            for (int i = 0; i < clicks.size(); i++) {
                while (clicks.get(i) - clicks.get(j) >= 1_000_000_000L) j++;
                best = Math.max(best, i - j + 1);
            }
            return best;
        }

        /** Standard deviation of clicks per whole second (lower = steadier). */
        public double consistency(long startNanos, double seconds) {
            int n = (int) Math.max(1, Math.floor(seconds));
            int[] per = new int[n];
            for (long c : clicks) {
                int s = (int) ((c - startNanos) / 1_000_000_000L);
                if (s >= 0 && s < n) per[s]++;
            }
            double mean = 0;
            for (int v : per) mean += v;
            mean /= n;
            double var = 0;
            for (int v : per) var += (v - mean) * (v - mean);
            return Math.sqrt(var / n);
        }
    }

    /** Crosshair-on-target ratio, overall and while strafing (A/D held). */
    public static final class Aim {
        private int ticks, onTarget, strafeTicks, strafeOnTarget;

        public void tick(boolean onTargetNow, boolean strafing) {
            ticks++;
            if (onTargetNow) onTarget++;
            if (strafing) {
                strafeTicks++;
                if (onTargetNow) strafeOnTarget++;
            }
        }

        public double overall() { return ticks == 0 ? 0 : (double) onTarget / ticks; }

        /** NaN when you never strafed (nothing to measure). */
        public double whileStrafing() { return strafeTicks == 0 ? Double.NaN : (double) strafeOnTarget / strafeTicks; }

        public double strafeShare() { return ticks == 0 ? 0 : (double) strafeTicks / ticks; }
    }

    /**
     * W-tap: a hit counts as "reset" when you were sprinting at the hit and your sprint stopped at some
     * point since your previous hit (the first hit only needs sprinting).
     */
    public static final class WTap {
        private int hits, good;
        private boolean resetSinceHit = true;

        public void tick(boolean sprinting) {
            if (!sprinting) resetSinceHit = true;
        }

        public void hit(boolean sprinting) {
            hits++;
            if (sprinting && resetSinceHit) good++;
            resetSinceHit = false;
        }

        public int hits() { return hits; }

        public double ratio() { return hits == 0 ? 0 : (double) good / hits; }
    }

    /** Bridging: blocks placed (from the inventory count), distance from the start and falls. */
    public static final class Bridge {
        private final double startX, startY, startZ;
        private int lastBlocks = -1, placed, falls;
        private double maxDistance;
        private boolean fallen;

        public Bridge(double x, double y, double z) {
            startX = x;
            startY = y;
            startZ = z;
        }

        public void tick(int blocksInInventory, double x, double y, double z) {
            if (lastBlocks >= 0 && blocksInInventory < lastBlocks) placed += lastBlocks - blocksInInventory;
            lastBlocks = blocksInInventory;
            maxDistance = Math.max(maxDistance, Math.hypot(x - startX, z - startZ));
            boolean below = y < startY - 2.5;
            if (below && !fallen) falls++;
            fallen = below;
        }

        public int placed() { return placed; }

        public int falls() { return falls; }

        public double distance() { return maxDistance; }

        public double blocksPerSecond(double seconds) { return seconds <= 0 ? 0 : placed / seconds; }
    }
}
