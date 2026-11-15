/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One JSON file per config entry (e.g. {@code modules/fps.json}). Writes are batched: callers mark an
 * entry dirty and {@link #flush()} writes them (every second and on shutdown), so dragging a slider
 * does not hit the disk on every frame. Files are written atomically (temp file + move).
 */
public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();

    private final Path root;
    private final Map<String, Supplier<Map<String, Object>>> dirty = new LinkedHashMap<>();
    private final Set<String> failed = new LinkedHashSet<>();

    public ConfigStore(Path root) {
        this.root = root;
    }

    public Path root() { return root; }

    /** File for an entry name such as "modules/example:ping" (":" becomes "__"). */
    public Path file(String name) {
        return root.resolve(name.replace(":", "__") + ".json");
    }

    /** Reads an entry; returns an empty map if missing or unreadable (a broken file is kept as .bak). */
    public Map<String, Object> read(String name) {
        Path f = file(name);
        if (!Files.isRegularFile(f)) return new LinkedHashMap<>();
        try {
            Map<String, Object> m = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), MAP);
            return m != null ? m : new LinkedHashMap<>();
        } catch (IOException | JsonParseException e) {
            try {
                Files.copy(f, f.resolveSibling(f.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // best effort
            }
            failed.add(name);
            return new LinkedHashMap<>();
        }
    }

    /** Names whose files could not be parsed since startup (for a warning in the UI/log). */
    public Set<String> failedReads() { return failed; }

    /** Schedules a write; the supplier is called at flush time so the latest state is saved. */
    public synchronized void markDirty(String name, Supplier<Map<String, Object>> snapshot) {
        dirty.put(name, snapshot);
    }

    public synchronized boolean hasPending() { return !dirty.isEmpty(); }

    /** Writes every dirty entry. Returns the number of files written. */
    public synchronized int flush() throws IOException {
        int n = 0;
        IOException first = null;
        for (var it = dirty.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            try {
                write(e.getKey(), e.getValue().get());
                n++;
            } catch (IOException ex) {
                if (first == null) first = ex;
            }
            it.remove();
        }
        if (first != null) throw first;
        return n;
    }

    public void write(String name, Map<String, Object> data) throws IOException {
        Path f = file(name);
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
