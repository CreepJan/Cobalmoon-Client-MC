/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.share;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.core.module.ModuleManager;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Shareable configuration codes: {@code CBM1:} + base64url(deflate(JSON)). A code contains only module
 * settings and HUD layouts — never accounts, tokens, server addresses or anything personal.
 */
public final class ConfigShare {
    public static final String PREFIX = "CBM1:";
    /** Limits that keep a malicious code from using lots of memory. */
    static final int MAX_CODE_CHARS = 512 * 1024;
    static final int MAX_JSON_BYTES = 4 * 1024 * 1024;

    private static final Gson GSON = new Gson();

    /** Modules whose state is personal or per-machine and never travels in a code. */
    public static final Set<String> NOT_SHARED = Set.of("server_profiles", "safe_server");

    /** What to include. */
    public enum Scope { ALL, HUD }

    public record ImportResult(int applied, List<String> unknownModules) {}

    private ConfigShare() {}

    /** Collects the state of every module (except {@code exclude}) for the scope. */
    public static Map<String, Object> collect(List<Module> modules, Scope scope, Set<String> exclude) {
        Map<String, Object> mods = new LinkedHashMap<>();
        for (Module m : modules) {
            if (exclude.contains(m.id())) continue;
            Map<String, Object> s = ModuleManager.snapshotOf(m);
            if (scope == Scope.HUD) {
                if (m.hudElements().isEmpty()) continue;
                Map<String, Object> hudOnly = new LinkedHashMap<>();
                hudOnly.put("enabled", s.get("enabled"));
                hudOnly.put("hud", s.get("hud"));
                s = hudOnly;
            }
            mods.put(m.id(), s);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", 1);
        root.put("scope", scope.name());
        root.put("modules", mods);
        return root;
    }

    public static String encode(Map<String, Object> data) {
        return pack(PREFIX, data);
    }

    /** Theme codes: {@code CBT1:} + the same packing. */
    public static final String THEME_PREFIX = "CBT1:";

    public static String encodeTheme(Map<String, Object> colors) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", 1);
        root.put("theme", colors);
        return pack(THEME_PREFIX, root);
    }

    /** Returns the colour map of a theme code. */
    public static Map<?, ?> decodeTheme(String code) {
        Map<String, Object> data = unpack(THEME_PREFIX, code);
        if (!(data.get("theme") instanceof Map<?, ?> m)) throw new IllegalArgumentException("code has no theme");
        return m;
    }

    static String pack(String prefix, Map<String, Object> data) {
        byte[] json = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(json);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** Parses a code; throws {@link IllegalArgumentException} with a readable reason if it is not valid. */
    public static Map<String, Object> decode(String code) {
        Map<String, Object> data = unpack(PREFIX, code);
        if (!(data.get("modules") instanceof Map)) throw new IllegalArgumentException("code has no module data");
        return data;
    }

    static Map<String, Object> unpack(String prefix, String code) {
        if (code == null) throw new IllegalArgumentException("empty");
        String c = code.replaceAll("\\s+", "");
        if (!c.startsWith(prefix)) throw new IllegalArgumentException("not a Cobalmoon code (must start with " + prefix + ")");
        if (c.length() > MAX_CODE_CHARS) throw new IllegalArgumentException("code too long");
        byte[] packed;
        try {
            packed = Base64.getUrlDecoder().decode(c.substring(prefix.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("damaged code (invalid characters)");
        }
        Inflater inf = new Inflater();
        inf.setInput(packed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw new IllegalArgumentException("damaged code (truncated)");
                out.write(buf, 0, n);
                if (out.size() > MAX_JSON_BYTES) throw new IllegalArgumentException("code expands to too much data");
            }
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("damaged code");
        } finally {
            inf.end();
        }
        try {
            Map<String, Object> data = GSON.fromJson(out.toString(StandardCharsets.UTF_8), new TypeToken<LinkedHashMap<String, Object>>() {}.getType());
            if (data == null) throw new IllegalArgumentException("empty code");
            return data;
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("damaged code (invalid data)");
        }
    }

    /** Applies decoded data. Modules not installed here are reported, not an error. */
    public static ImportResult apply(ModuleManager manager, Map<String, Object> data, Set<String> exclude) {
        Map<?, ?> mods = (Map<?, ?>) data.get("modules");
        int applied = 0;
        List<String> unknown = new java.util.ArrayList<>();
        for (var e : mods.entrySet()) {
            String id = String.valueOf(e.getKey());
            if (exclude.contains(id) || !(e.getValue() instanceof Map<?, ?> state)) continue;
            var module = manager.get(id);
            if (module.isEmpty()) {
                unknown.add(id);
                continue;
            }
            manager.importState(module.get(), state);
            applied++;
        }
        return new ImportResult(applied, unknown);
    }
}
