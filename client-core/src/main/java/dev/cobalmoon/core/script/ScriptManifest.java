/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.script;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * {@code script.json} of a script folder:
 * <pre>{"id": "session-timer", "name": "Session timer", "version": "1.0", "author": "you",
 *  "description": "…", "main": "main.lua", "permissions": ["hud.draw"]}</pre>
 */
public record ScriptManifest(String id, String name, String version, String author, String description, String main,
                             Set<Permission> permissions, List<String> unknownPermissions) {

    private record Raw(String id, String name, String version, String author, String description, String main, List<String> permissions) {}

    /** Parses and validates; throws {@link IllegalArgumentException} with a readable reason. */
    public static ScriptManifest parse(String json) {
        Raw r;
        try {
            r = new Gson().fromJson(json, Raw.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("script.json is not valid JSON");
        }
        if (r == null) throw new IllegalArgumentException("script.json is empty");
        if (r.id() == null || !r.id().matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("id must be 1-32 characters: a-z, 0-9, _ or -");
        }
        String main = r.main() == null || r.main().isBlank() ? "main.lua" : r.main();
        if (!main.matches("[A-Za-z0-9_-]+\\.lua")) throw new IllegalArgumentException("main must be a .lua file in the script folder");
        Set<Permission> perms = EnumSet.noneOf(Permission.class);
        List<String> unknown = new ArrayList<>();
        if (r.permissions() != null) {
            for (String p : r.permissions()) {
                Permission.of(p).ifPresentOrElse(perms::add, () -> unknown.add(p));
            }
        }
        return new ScriptManifest(r.id(), nonBlank(r.name(), r.id()), nonBlank(r.version(), "?"), nonBlank(r.author(), "?"),
                nonBlank(r.description(), ""), main, perms, List.copyOf(unknown));
    }

    private static String nonBlank(String s, String fallback) {
        if (s == null || s.isBlank()) return fallback;
        String clean = s.replaceAll("\\p{Cntrl}", "");
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }
}
