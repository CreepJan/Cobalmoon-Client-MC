/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core;

import dev.cobalmoon.api.CobalmoonApi;
import dev.cobalmoon.api.CobalmoonEntrypoint;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import java.util.List;

/**
 * Calls every {@code "cobalmoon"} entrypoint declared by installed mods.
 * No allow- or block-lists: anything the player installed is loaded. A failing addon is
 * logged and skipped so it cannot take the client down.
 */
final class AddonLoader {
    record Result(int loaded, int failed) {}

    private AddonLoader() {}

    static Result run(FabricLoader loader, CobalmoonApi api) {
        List<EntrypointContainer<CobalmoonEntrypoint>> containers;
        try {
            containers = loader.getEntrypointContainers(CobalmoonEntrypoint.KEY, CobalmoonEntrypoint.class);
        } catch (RuntimeException e) {
            CobalmoonClient.LOG.error("Could not instantiate Cobalmoon addon entrypoints", e);
            return new Result(0, 1);
        }
        int ok = 0;
        int failed = 0;
        for (EntrypointContainer<CobalmoonEntrypoint> c : containers) {
            String modId = c.getProvider().getMetadata().getId();
            try {
                c.getEntrypoint().onCobalmoonInit(api);
                ok++;
                CobalmoonClient.LOG.info("Addon initialised: {}", modId);
            } catch (Throwable t) {
                failed++;
                CobalmoonClient.LOG.error("Addon '{}' failed to initialise; continuing without it", modId, t);
            }
        }
        return new Result(ok, failed);
    }
}
