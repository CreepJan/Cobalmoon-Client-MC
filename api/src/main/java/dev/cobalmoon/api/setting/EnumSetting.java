/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.setting;

import java.util.function.Function;

/**
 * One value of an enum, stored by constant name. Shown as a button that cycles values.
 * Optionally give each constant a display label.
 */
public final class EnumSetting<E extends Enum<E>> extends Setting<E> {
    private final Class<E> type;
    private final Function<E, String> label;

    public EnumSetting(String key, String name, E defaultValue) {
        this(key, name, defaultValue, Enum::name);
    }

    public EnumSetting(String key, String name, E defaultValue, Function<E, String> label) {
        super(key, name, defaultValue);
        this.type = defaultValue.getDeclaringClass();
        this.label = label;
    }

    public E[] values() { return type.getEnumConstants(); }

    public String label(E value) { return label.apply(value); }

    /** Moves to the next (or previous, for {@code delta < 0}) constant, wrapping around. */
    public void cycle(int delta) {
        E[] all = values();
        int i = Math.floorMod(get().ordinal() + delta, all.length);
        set(all[i]);
    }

    @Override public Object toJson() { return get().name(); }

    @Override
    public void fromJson(Object json) {
        if (json instanceof String s) {
            for (E e : values()) {
                if (e.name().equalsIgnoreCase(s)) {
                    set(e);
                    return;
                }
            }
        }
        set(defaultValue());
    }
}
