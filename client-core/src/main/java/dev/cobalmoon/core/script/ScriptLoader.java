/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.script;

import dev.cobalmoon.core.CobalmoonClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Finds scripts in {@code config/cobalmoon/scripts/<folder>/script.json}. New folders need a game restart. */
public final class ScriptLoader {
    /** Example scripts copied the first time the scripts folder is created (they start without permissions). */
    private static final String[][] EXAMPLES = {
            {"session-timer", "script.json"}, {"session-timer", "main.lua"},
            {"best-combo", "script.json"}, {"best-combo", "main.lua"},
    };

    private ScriptLoader() {}

    public static List<ScriptModule> load(Path configDir) {
        Path scripts = configDir.resolve("scripts");
        if (!Files.exists(scripts)) installExamples(scripts);
        ScriptGrants grants = new ScriptGrants(scripts.resolve("grants.json"));
        Path data = configDir.resolve("script-data");
        List<ScriptModule> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(scripts)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path manifest = dir.resolve("script.json");
                if (!Files.isRegularFile(manifest)) continue;
                try {
                    ScriptManifest m = ScriptManifest.parse(Files.readString(manifest, StandardCharsets.UTF_8));
                    if (out.stream().anyMatch(s -> s.manifest().id().equals(m.id()))) {
                        CobalmoonClient.LOG.warn("Skipping script in {}: id '{}' is already used", dir.getFileName(), m.id());
                        continue;
                    }
                    out.add(new ScriptModule(dir, m, grants, data));
                } catch (IllegalArgumentException | IOException e) {
                    CobalmoonClient.LOG.warn("Skipping script in {}: {}", dir.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            CobalmoonClient.LOG.warn("Could not list scripts", e);
        }
        return out;
    }

    private static void installExamples(Path scripts) {
        try {
            for (String[] f : EXAMPLES) {
                try (InputStream in = ScriptLoader.class.getResourceAsStream("/assets/cobalmoon/examples/scripts/" + f[0] + "/" + f[1])) {
                    if (in == null) continue;
                    Path target = scripts.resolve(f[0]).resolve(f[1]);
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target);
                }
            }
        } catch (IOException e) {
            CobalmoonClient.LOG.warn("Could not install example scripts", e);
        }
    }
}
