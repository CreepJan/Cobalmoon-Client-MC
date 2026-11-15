/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.fight;

import java.util.List;

/**
 * Result of one fight, as stored in {@code config/cobalmoon/fights.json}.
 *
 * @param track samples {seconds, selfX, selfZ, selfYaw, opponentX, opponentZ} at 10 Hz (NaN when unknown)
 * @param marks events {seconds, type (0 = your hit landed, 1 = you were hit), x, z}
 */
public record FightRecord(
        long timestamp,
        long durationMs,
        String opponent,
        Outcome outcome,
        int swings,
        int attacks,
        int hits,
        int crits,
        int maxCombo,
        double avgReach,
        double maxReach,
        float damageDealt,
        float damageTaken,
        int avgReactionMs,
        List<float[]> track,
        List<float[]> marks) {

    public enum Outcome { WIN, LOSS, UNKNOWN }

    /** Landed hits per swing (0..1). Uses attacks when fewer clicks were seen (e.g. attacks bound to a key). */
    public double accuracy() {
        int tries = Math.max(swings, attacks);
        return tries == 0 ? 0 : Math.min(1.0, (double) hits / tries);
    }

    public boolean hasTrack() {
        return track != null && track.size() > 1;
    }

    public FightRecord withoutTrack() {
        return new FightRecord(timestamp, durationMs, opponent, outcome, swings, attacks, hits, crits, maxCombo,
                avgReach, maxReach, damageDealt, damageTaken, avgReactionMs, List.of(), List.of());
    }
}
