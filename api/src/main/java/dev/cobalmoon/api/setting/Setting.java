/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.setting;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A typed, persisted option of a module. Values are stored as plain JSON-compatible objects
 * ({@link Boolean}, {@link Number}, {@link String}) so the API needs no JSON library.
 *
 * @param <T> value type
 */
public abstract class Setting<T> {
    private final String key;
    private final String name;
    private final T defaultValue;
    private T value;
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

    protected Setting(String key, String name, T defaultValue) {
        if (key == null || !key.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("setting key must match [a-z0-9_]+: " + key);
        }
        this.key = key;
        this.name = Objects.requireNonNull(name, "name");
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        this.value = defaultValue;
    }

    /** Stable id used in config files. */
    public final String key() { return key; }

    /** Display name. */
    public final String name() { return name; }

    public final T defaultValue() { return defaultValue; }

    public final T get() { return value; }

    /** Sets the value (after {@link #sanitize}) and notifies listeners if it changed. */
    public final void set(T newValue) {
        T clean = sanitize(Objects.requireNonNull(newValue, "value"));
        if (clean.equals(value)) return;
        value = clean;
        for (Consumer<T> l : listeners) l.accept(clean);
    }

    public final void reset() { set(defaultValue); }

    /** Called with the new value whenever it changes. */
    public final void onChange(Consumer<T> listener) { listeners.add(listener); }

    /** Normalises a value (clamping, snapping). Must not throw for any non-null input. */
    protected T sanitize(T value) { return value; }

    /** JSON-compatible representation of the current value. */
    public abstract Object toJson();

    /**
     * Loads a value read from config. Invalid or missing data falls back to the default
     * instead of throwing, so a hand-edited or outdated config never breaks the client.
     */
    public abstract void fromJson(Object json);
}
