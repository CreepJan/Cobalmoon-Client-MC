/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.hud;

import dev.cobalmoon.api.module.Module;

import java.util.Objects;

/**
 * Something drawn on the in-game HUD that the player can move, scale and restyle in the HUD editor.
 * Register it from a module with {@code hud(new MyElement(...))}.
 */
public abstract class HudElement {
    private final String id;
    private final String name;
    private final HudLayout defaults;
    private final HudLayout layout;
    private Module owner;

    protected HudElement(String id, String name, HudLayout defaults) {
        if (id == null || !id.matches("([a-z0-9_.-]+:)?[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid HUD element id: " + id);
        }
        this.id = id;
        this.name = Objects.requireNonNull(name);
        this.defaults = defaults.copy();
        this.layout = defaults.copy();
    }

    public final String id() { return id; }
    public final String name() { return name; }

    /** Current, editable layout. */
    public final HudLayout layout() { return layout; }

    public final void resetLayout() { layout.copyFrom(defaults); }

    public final Module owner() { return owner; }

    /** Called by {@link Module#hud}. */
    public final void attach(Module module) {
        if (owner != null) throw new IllegalStateException("HUD element " + id + " already belongs to " + owner.id());
        owner = module;
    }

    /** Visible in game: owner enabled (if any) and {@link #shouldRender()}. */
    public boolean isVisible() {
        return (owner == null || owner.isEnabled()) && shouldRender();
    }

    /** Override to hide the element temporarily (e.g. no data yet). The editor always shows it. */
    protected boolean shouldRender() { return true; }

    /** Unscaled width. {@code preview} is true in the HUD editor, where sample content may be shown. */
    public abstract int width(HudCanvas canvas, boolean preview);

    public abstract int height(HudCanvas canvas, boolean preview);

    /** Draws the content at local (0, 0). The background (if enabled) is already drawn. */
    public abstract void render(HudCanvas canvas, boolean preview);

    /** Whether the renderer draws {@link HudLayout#background} behind the element. */
    public boolean supportsBackground() { return true; }
}
