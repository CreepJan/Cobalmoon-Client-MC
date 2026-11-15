/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api;

import java.util.Objects;
import java.util.Optional;

/** Static access to the running client. */
public final class Cobalmoon {
    /** Fabric mod id of the client core. */
    public static final String MOD_ID = "cobalmoon";

    private static volatile CobalmoonApi instance;

    private Cobalmoon() {}

    /**
     * The running client's API.
     *
     * @throws IllegalStateException if called before Cobalmoon has started
     */
    public static CobalmoonApi api() {
        CobalmoonApi api = instance;
        if (api == null) {
            throw new IllegalStateException("Cobalmoon Client has not started yet; use the 'cobalmoon' entrypoint");
        }
        return api;
    }

    /** The running client's API, or empty if Cobalmoon is absent or not started yet. */
    public static Optional<CobalmoonApi> find() {
        return Optional.ofNullable(instance);
    }

    /**
     * Installs the implementation. Called once by the client core; not for addons.
     *
     * @throws IllegalStateException if an implementation is already installed
     */
    public static synchronized void install(CobalmoonApi api) {
        Objects.requireNonNull(api, "api");
        if (instance != null) {
            throw new IllegalStateException("Cobalmoon API is already installed");
        }
        instance = api;
    }

    /** Test hook: removes the installed implementation. */
    static synchronized void reset() {
        instance = null;
    }
}
