/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.fight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Last fights on this computer (newest last). Only the newest {@link #KEEP_TRACKS} keep their replay track. */
public final class FightHistory {
    public static final int KEEP = 50;
    public static final int KEEP_TRACKS = 10;
    private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    private final Path file;
    private final List<FightRecord> records = new ArrayList<>();

    public FightHistory(Path file) {
        this.file = file;
        load();
    }

    public List<FightRecord> records() {
        return Collections.unmodifiableList(records);
    }

    public FightRecord latest() {
        return records.isEmpty() ? null : records.get(records.size() - 1);
    }

    /** Fights that still have a replay, newest last. */
    public List<FightRecord> replays() {
        return records.stream().filter(FightRecord::hasTrack).toList();
    }

    public void add(FightRecord r) {
        records.add(r);
        while (records.size() > KEEP) records.remove(0);
        int withTrack = 0;
        for (int i = records.size() - 1; i >= 0; i--) {
            if (records.get(i).hasTrack() && ++withTrack > KEEP_TRACKS) records.set(i, records.get(i).withoutTrack());
        }
        save();
    }

    public void clear() {
        records.clear();
        save();
    }

    /** Difference between {@code r} and the average of the fights before it, or NaN without history. */
    public double delta(FightRecord r, ToDoubleFunction<FightRecord> metric) {
        List<FightRecord> before = records.stream().filter(x -> x != r && x.timestamp() <= r.timestamp()).toList();
        if (before.isEmpty()) return Double.NaN;
        double avg = before.stream().mapToDouble(metric).average().orElse(Double.NaN);
        return metric.applyAsDouble(r) - avg;
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            List<FightRecord> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<FightRecord>>() {}.getType());
            if (loaded != null) loaded.stream().filter(r -> r != null && r.outcome() != null).forEach(records::add);
        } catch (Exception e) {
            // A broken history is not worth crashing over; keep it aside and start fresh.
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(records), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            dev.cobalmoon.core.CobalmoonClient.LOG.warn("Could not save fight history", e);
        }
    }
}
