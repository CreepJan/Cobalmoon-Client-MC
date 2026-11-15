/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core;

import dev.cobalmoon.api.setting.BooleanSetting;
import dev.cobalmoon.api.setting.Setting;
import dev.cobalmoon.core.config.ConfigStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Global client preferences, saved in {@code config/cobalmoon/client.json}. */
public final class ClientSettings {
    private static final String ENTRY = "client";

    public final BooleanSetting reduceMotion = new BooleanSetting("reduce_motion", "cobalmoon.setting.client.reduce_motion", false);
    public final BooleanSetting snapping = new BooleanSetting("snapping", "cobalmoon.setting.client.snapping", true);
    public final BooleanSetting editorHints = new BooleanSetting("editor_hints", "cobalmoon.setting.client.editor_hints", true);

    private final ConfigStore store;

    public ClientSettings(ConfigStore store) {
        this.store = store;
        Map<String, Object> saved = store.read(ENTRY);
        for (Setting<?> s : all()) {
            if (saved.containsKey(s.key())) s.fromJson(saved.get(s.key()));
            s.onChange(v -> store.markDirty(ENTRY, this::snapshot));
        }
    }

    public List<Setting<?>> all() {
        return List.of(reduceMotion, snapping, editorHints);
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Setting<?> s : all()) m.put(s.key(), s.toJson());
        return m;
    }
}
