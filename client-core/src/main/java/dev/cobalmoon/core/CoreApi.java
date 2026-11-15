/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core;

import dev.cobalmoon.api.Cobalmoon;
import dev.cobalmoon.api.CobalmoonApi;
import dev.cobalmoon.api.event.EventBus;
import dev.cobalmoon.api.module.ModuleRegistry;
import dev.cobalmoon.core.module.ModuleManager;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/** The core's implementation of the public API. */
public final class CoreApi implements CobalmoonApi {
    private final String clientVersion;
    private final String minecraftVersion;
    private final ModuleManager modules;
    private final EventBus events;

    public CoreApi(String clientVersion, String minecraftVersion, ModuleManager modules, EventBus events) {
        this.clientVersion = clientVersion;
        this.minecraftVersion = minecraftVersion;
        this.modules = modules;
        this.events = events;
    }

    static String clientVersion(FabricLoader loader) {
        return loader.getModContainer(Cobalmoon.MOD_ID)
                .map(ModContainer::getMetadata)
                .map(m -> m.getVersion().getFriendlyString())
                .orElse("dev");
    }

    static String minecraftVersion(FabricLoader loader) {
        return loader.getModContainer("minecraft")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override public String clientVersion() { return clientVersion; }
    @Override public String minecraftVersion() { return minecraftVersion; }
    @Override public ModuleRegistry modules() { return modules; }
    @Override public EventBus events() { return events; }

    public ModuleManager moduleManager() { return modules; }
}
