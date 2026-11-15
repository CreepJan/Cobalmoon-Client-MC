/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Permissions the player granted, per script. A grant is tied to the SHA-256 of the script's files:
 * if the script changes (an update, or someone editing it), it must be granted again.
 */
public final class ScriptGrants {
    private record Grant(String hash, List<String> permissions) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<String, Grant> grants = new LinkedHashMap<>();

    public ScriptGrants(Path file) {
        this.file = file;
        try {
            if (Files.isRegularFile(file)) {
                Map<String, Grant> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<LinkedHashMap<String, Grant>>() {}.getType());
                if (loaded != null) grants.putAll(loaded);
            }
        } catch (Exception e) {
            // unreadable grants: everything must be granted again (safe default)
        }
    }

    public static String hash(String manifestJson, String source) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(manifestJson.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when the player granted exactly these permissions to this exact version of the script. */
    public boolean isGranted(String id, String hash, Set<Permission> requested) {
        Grant g = grants.get(id);
        if (g == null || !g.hash().equals(hash)) return false;
        Set<String> want = new TreeSet<>();
        requested.forEach(p -> want.add(p.id));
        return new TreeSet<>(g.permissions()).containsAll(want);
    }

    public void grant(String id, String hash, Set<Permission> permissions) {
        grants.put(id, new Grant(hash, permissions.stream().map(p -> p.id).sorted().toList()));
        save();
    }

    public void revoke(String id) {
        grants.remove(id);
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(grants), StandardCharsets.UTF_8);
        } catch (IOException e) {
            dev.cobalmoon.core.CobalmoonClient.LOG.warn("Could not save script permissions", e);
        }
    }
}
