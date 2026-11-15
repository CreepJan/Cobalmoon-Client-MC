/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api;

/**
 * Implemented by any Fabric mod that wants to extend Cobalmoon Client.
 *
 * <p>Declare it in your {@code fabric.mod.json}; no other registration is needed:
 * <pre>{@code
 * "entrypoints": {
 *   "cobalmoon": [ "com.example.MyCobalmoonAddon" ]
 * }
 * }</pre>
 *
 * <p>Cobalmoon calls every entrypoint once, on the client thread, after its own core has started.
 * An exception thrown by one addon is logged and does not stop the client or the other addons.
 * If Cobalmoon is not installed this entrypoint is simply never called, so addons can stay
 * optional dependencies.
 */
@FunctionalInterface
public interface CobalmoonEntrypoint {

    /** Fabric entrypoint key. */
    String KEY = "cobalmoon";

    void onCobalmoonInit(CobalmoonApi api);
}
