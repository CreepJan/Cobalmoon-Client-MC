/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.fight;

import java.util.ArrayList;
import java.util.List;

/** Accumulates one fight from plain events (no Minecraft types, so it is unit-testable). */
public final class FightStats {
    /** Reaction samples longer than this are not reactions (you were not fighting back yet). */
    static final long MAX_REACTION_NANOS = 1_500_000_000L;
    /** Track length cap: 10 Hz for 5 minutes. */
    static final int MAX_SAMPLES = 3000;

    private final long start;
    private final String opponent;
    private long lastCombat;
    private int swings, attacks, hits, crits, combo, maxCombo;
    private double reachSum, maxReach;
    private float damageDealt, damageTaken;
    private final List<Long> reactions = new ArrayList<>();
    private long pendingHurt = -1;
    private final List<float[]> track = new ArrayList<>();
    private final List<float[]> marks = new ArrayList<>();

    public FightStats(long startNanos, String opponent) {
        this.start = startNanos;
        this.lastCombat = startNanos;
        this.opponent = opponent;
    }

    private float t(long nanos) {
        return (nanos - start) / 1e9f;
    }

    public String opponent() { return opponent; }
    public long lastCombat() { return lastCombat; }
    public int hits() { return hits; }
    public int combo() { return combo; }

    public void swing(long nanos) {
        swings++;
    }

    public void attack(long nanos) {
        attacks++;
        lastCombat = nanos;
        if (pendingHurt >= 0) {
            long r = nanos - pendingHurt;
            if (r <= MAX_REACTION_NANOS) reactions.add(r / 1_000_000);
            pendingHurt = -1;
        }
    }

    public void hit(long nanos, double distance, boolean critical, float damage, double x, double z) {
        hits++;
        if (critical) crits++;
        combo++;
        maxCombo = Math.max(maxCombo, combo);
        reachSum += distance;
        maxReach = Math.max(maxReach, distance);
        if (damage > 0) damageDealt += damage;
        lastCombat = nanos;
        marks.add(new float[]{t(nanos), 0, (float) x, (float) z});
    }

    public void hurt(long nanos, float damage, double x, double z) {
        combo = 0;
        if (damage > 0) damageTaken += damage;
        lastCombat = nanos;
        pendingHurt = nanos;
        marks.add(new float[]{t(nanos), 1, (float) x, (float) z});
    }

    public void sample(long nanos, double sx, double sz, float yaw, double ox, double oz) {
        if (track.size() < MAX_SAMPLES) track.add(new float[]{t(nanos), (float) sx, (float) sz, yaw, (float) ox, (float) oz});
    }

    public FightRecord finish(long endNanos, FightRecord.Outcome outcome, long timestampMillis) {
        int avgReaction = reactions.isEmpty() ? -1 : (int) Math.round(reactions.stream().mapToLong(Long::longValue).average().orElse(0));
        return new FightRecord(timestampMillis, Math.max(0, (endNanos - start) / 1_000_000), opponent, outcome,
                swings, attacks, hits, crits, maxCombo,
                hits == 0 ? 0 : reachSum / hits, maxReach, damageDealt, damageTaken, avgReaction,
                List.copyOf(track), List.copyOf(marks));
    }
}
