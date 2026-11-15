/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.module;

import java.util.List;
import java.util.Optional;

/** All modules known to the client. Obtain it with {@code api.modules()}. */
public interface ModuleRegistry {
    /**
     * Registers a module, loads its saved config (enabled state, settings, HUD layout) and, if
     * enabled, activates it.
     *
     * @throws IllegalArgumentException if another module already uses the same id
     */
    <M extends Module> M register(M module);

    Optional<Module> get(String id);

    /** Modules in registration order. */
    List<Module> all();
}
