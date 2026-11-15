/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.module;

/** Groups modules in the menu. */
public enum Category {
    HUD,
    VISUAL,
    GAMEPLAY,
    /** Fight analysis and practice: measures your play, never plays for you. */
    TRAINING,
    PERFORMANCE,
    OTHER;

    /** Translation key, e.g. {@code cobalmoon.category.hud}. */
    public String translationKey() {
        return "cobalmoon.category." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
