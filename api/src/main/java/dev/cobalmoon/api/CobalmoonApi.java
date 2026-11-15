/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api;

import dev.cobalmoon.api.event.EventBus;
import dev.cobalmoon.api.module.ModuleRegistry;

/**
 * Entry point into Cobalmoon Client for third-party mods.
 *
 * <p>Obtain the instance in {@link CobalmoonEntrypoint#onCobalmoonInit(CobalmoonApi)} or, once the
 * client has started, through {@link Cobalmoon#api()}.
 *
 * <p>After 1.0, new methods are only ever added as {@code default} methods so addons compiled
 * against an older API keep loading.
 */
public interface CobalmoonApi {

    /** Version of the API contract this client implements. Bumped only on breaking changes. */
    int API_LEVEL = 1;

    /** Cobalmoon Client version, e.g. {@code 0.2.0}. */
    String clientVersion();

    /** Minecraft version the client is running on, e.g. {@code 26.3}. */
    String minecraftVersion();

    /** API level of the running client. Compare with {@link #API_LEVEL} to detect older clients. */
    default int apiLevel() {
        return API_LEVEL;
    }

    /** Register and look up modules. */
    ModuleRegistry modules();

    /** Client event bus (see {@link dev.cobalmoon.api.event.Events}). */
    EventBus events();
}
