/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.ui;

import net.minecraft.locale.Language;

/** Translates a key if a translation exists, otherwise shows the text as given (for addon modules). */
public final class Tr {
    private Tr() {}

    public static String of(String keyOrText) {
        Language lang = Language.getInstance();
        return lang.has(keyOrText) ? lang.getOrDefault(keyOrText) : keyOrText;
    }

    public static String of(String key, Object... args) {
        Language lang = Language.getInstance();
        return lang.has(key) ? String.format(lang.getOrDefault(key), args) : key;
    }
}
