/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.module;

import dev.cobalmoon.api.event.EventBus;
import dev.cobalmoon.api.hud.HudElement;
import dev.cobalmoon.api.setting.Setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Base class of every Cobalmoon module, built-in or third-party.
 *
 * <pre>{@code
 * public final class PingModule extends Module {
 *     private final BooleanSetting showMs = setting(new BooleanSetting("show_ms", "Show ms", true));
 *
 *     public PingModule() {
 *         super("example:ping", "Ping", "Shows your latency", Category.HUD);
 *         hud(new TextHudElement("example:ping", "Ping", Anchor.TOP_RIGHT, 4, 4) {
 *             protected List<String> lines(boolean preview) {
 *                 return List.of(labeled("Ping", 42 + (showMs.get() ? " ms" : "")));
 *             }
 *         });
 *         on(Events.ClientTick.class, e -> { ... });   // only called while enabled
 *     }
 * }
 * }</pre>
 *
 * <p>Settings, HUD elements and event handlers are declared in the constructor. Handlers declared
 * with {@link #on} are subscribed when the module is enabled and removed when it is disabled,
 * so a disabled module costs nothing per frame.
 */
public abstract class Module {
    private final String id;
    private final String name;
    private final String description;
    private final Category category;
    private final List<Setting<?>> settings = new ArrayList<>();
    private final List<HudElement> hudElements = new ArrayList<>();
    private final List<Handler<?>> handlers = new ArrayList<>();
    private final List<EventBus.Subscription> active = new ArrayList<>();
    private final List<Consumer<Module>> toggleListeners = new CopyOnWriteArrayList<>();
    private EventBus bus;
    private boolean enabled;

    private record Handler<E>(Class<E> type, Consumer<? super E> consumer) {
        EventBus.Subscription subscribe(EventBus bus) { return bus.subscribe(type, consumer); }
    }

    /**
     * @param id          unique id; use {@code namespace:name} for third-party modules (built-ins use a bare name)
     * @param name        display name or translation key
     * @param description short description or translation key
     */
    protected Module(String id, String name, String description, Category category) {
        if (id == null || !id.matches("([a-z0-9_.-]+:)?[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid module id: " + id);
        }
        this.id = id;
        this.name = Objects.requireNonNull(name);
        this.description = Objects.requireNonNull(description);
        this.category = Objects.requireNonNull(category);
    }

    public final String id() { return id; }
    public final String name() { return name; }
    public final String description() { return description; }
    public final Category category() { return category; }
    public final List<Setting<?>> settings() { return Collections.unmodifiableList(settings); }
    public final List<HudElement> hudElements() { return Collections.unmodifiableList(hudElements); }
    public final boolean isEnabled() { return enabled; }

    /** Whether the module starts enabled the very first time (no saved config). */
    public boolean enabledByDefault() { return false; }

    /** Registers a setting; call from the constructor. */
    protected final <S extends Setting<?>> S setting(S setting) {
        for (Setting<?> s : settings) {
            if (s.key().equals(setting.key())) throw new IllegalArgumentException("duplicate setting " + setting.key());
        }
        settings.add(setting);
        return setting;
    }

    /** Registers a HUD element owned by this module; it is drawn only while the module is enabled. */
    protected final <H extends HudElement> H hud(H element) {
        element.attach(this);
        hudElements.add(element);
        return element;
    }

    /** Declares an event handler that is active only while the module is enabled. */
    protected final <E> void on(Class<E> type, Consumer<? super E> handler) {
        Handler<E> h = new Handler<>(type, handler);
        handlers.add(h);
        if (enabled && bus != null) active.add(h.subscribe(bus));
    }

    protected void onEnable() {}

    protected void onDisable() {}

    /** Enables or disables the module (hot: takes effect immediately). */
    public final void setEnabled(boolean value) {
        if (value == enabled) return;
        enabled = value;
        if (bus != null) {
            if (value) {
                for (Handler<?> h : handlers) active.add(h.subscribe(bus));
                onEnable();
            } else {
                onDisable();
                active.forEach(EventBus.Subscription::cancel);
                active.clear();
            }
        }
        for (Consumer<Module> l : toggleListeners) l.accept(this);
    }

    public final void toggle() { setEnabled(!enabled); }

    /** Called with this module after every enable/disable. */
    public final void onToggle(Consumer<Module> listener) { toggleListeners.add(listener); }

    /**
     * Connects the module to the client's event bus. Called once by the module registry;
     * not meant for addons.
     */
    public final void bind(EventBus bus) {
        if (this.bus != null) throw new IllegalStateException("module " + id + " is already registered");
        this.bus = Objects.requireNonNull(bus);
        if (enabled) {
            for (Handler<?> h : handlers) active.add(h.subscribe(bus));
            onEnable();
        }
    }

    public final Setting<?> setting(String key) {
        for (Setting<?> s : settings) if (s.key().equals(key)) return s;
        return null;
    }
}
