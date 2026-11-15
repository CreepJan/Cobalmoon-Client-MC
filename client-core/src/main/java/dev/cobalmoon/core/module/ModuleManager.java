/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.module;

import dev.cobalmoon.api.event.EventBus;
import dev.cobalmoon.api.hud.HudElement;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.module.ModuleRegistry;
import dev.cobalmoon.api.setting.Setting;
import dev.cobalmoon.core.config.ConfigStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of all modules. Loads each module's saved state when it is registered and saves it
 * (batched) whenever the module is toggled, a setting changes or its HUD layout is edited.
 *
 * <p>File format ({@code config/cobalmoon/modules/<id>.json}):
 * <pre>{"enabled": true, "settings": {"key": value}, "hud": {"elementId": {layout}}}</pre>
 */
public final class ModuleManager implements ModuleRegistry {
    private final EventBus bus;
    private final ConfigStore store;
    private final Map<String, Module> modules = new LinkedHashMap<>();

    public ModuleManager(EventBus bus, ConfigStore store) {
        this.bus = bus;
        this.store = store;
    }

    static String entry(Module m) {
        return "modules/" + m.id();
    }

    @Override
    public synchronized <M extends Module> M register(M module) {
        if (modules.containsKey(module.id())) {
            throw new IllegalArgumentException("a module with id '" + module.id() + "' is already registered");
        }
        for (Module other : modules.values()) {
            for (HudElement a : other.hudElements()) {
                for (HudElement b : module.hudElements()) {
                    if (a.id().equals(b.id())) throw new IllegalArgumentException("duplicate HUD element id " + b.id());
                }
            }
        }
        apply(module, store.read(entry(module)));
        modules.put(module.id(), module);
        module.bind(bus);
        module.onToggle(this::markDirty);
        for (Setting<?> s : module.settings()) s.onChange(v -> markDirty(module));
        return module;
    }

    /** Applies saved state. Settings and layouts that are missing keep their defaults. */
    static void apply(Module module, Map<String, Object> saved) {
        if (saved.get("settings") instanceof Map<?, ?> settings) {
            for (Setting<?> s : module.settings()) {
                if (settings.containsKey(s.key())) s.fromJson(settings.get(s.key()));
            }
        }
        if (saved.get("hud") instanceof Map<?, ?> hud) {
            for (HudElement e : module.hudElements()) {
                if (hud.get(e.id()) instanceof Map<?, ?> layout) e.layout().fromJson(layout);
            }
        }
        boolean enabled = saved.get("enabled") instanceof Boolean b ? b : module.enabledByDefault();
        // Test hook: start with every module on, as if saved that way (catches modules that touch the game too early).
        if (Boolean.getBoolean("cobalmoon.test.enableAllModules")) enabled = true;
        module.setEnabled(enabled);
    }

    static Map<String, Object> snapshot(Module module) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", module.isEnabled());
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Setting<?> s : module.settings()) {
            Object json = s.toJson();
            if (json != null) settings.put(s.key(), json);
        }
        out.put("settings", settings);
        if (!module.hudElements().isEmpty()) {
            Map<String, Object> hud = new LinkedHashMap<>();
            for (HudElement e : module.hudElements()) hud.put(e.id(), e.layout().toJson());
            out.put("hud", hud);
        }
        return out;
    }

    /** Schedules a save of the module (e.g. after editing its HUD layout). */
    public void markDirty(Module module) {
        store.markDirty(entry(module), () -> snapshot(module));
    }

    @Override
    public synchronized Optional<Module> get(String id) {
        return Optional.ofNullable(modules.get(id));
    }

    @Override
    public synchronized List<Module> all() {
        return Collections.unmodifiableList(new ArrayList<>(modules.values()));
    }

    /** Every HUD element of every module, in registration order. */
    public synchronized List<HudElement> hudElements() {
        List<HudElement> out = new ArrayList<>();
        for (Module m : modules.values()) out.addAll(m.hudElements());
        return out;
    }

    public ConfigStore store() { return store; }
}
