/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.script;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.cobalmoon.api.event.Events;
import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.TextHudElement;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.ActionSetting;
import dev.cobalmoon.api.setting.InfoSetting;
import dev.cobalmoon.core.CobalmoonClient;
import dev.cobalmoon.core.input.Clicks;
import dev.cobalmoon.core.ui.Tr;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A community script shown as a module. It only runs after the player grants the permissions its
 * manifest asks for; any error turns it off and is shown in its settings.
 */
public final class ScriptModule extends Module {
    /** Thrown when enabling a script whose permissions were not granted (not an error: just turned off). */
    public static final class NotGranted extends RuntimeException {
        NotGranted(String id) {
            super("Script '" + id + "' needs its permissions granted first");
        }
    }

    private final Path folder;
    private final ScriptManifest manifest;
    private final ScriptGrants grants;
    private final Path dataFile;
    private ScriptRuntime runtime;
    private List<String> hudLines = List.of();
    private String status = "";

    public ScriptModule(Path folder, ScriptManifest manifest, ScriptGrants grants, Path dataDir) {
        super("script." + manifest.id(), manifest.name(), manifest.description(), Category.SCRIPTS);
        this.folder = folder;
        this.manifest = manifest;
        this.grants = grants;
        this.dataFile = dataDir.resolve(manifest.id() + ".json");

        setting(new InfoSetting("about", this::about));
        setting(new ActionSetting("grant", "cobalmoon.script.grant", this::grantAndEnable));
        setting(new ActionSetting("revoke", "cobalmoon.script.revoke", () -> {
            grants.revoke(manifest.id());
            setEnabled(false);
            status = Tr.of("cobalmoon.script.revoked");
        }));
        setting(new ActionSetting("reload", "cobalmoon.script.reload", () -> {
            if (isEnabled()) {
                setEnabled(false);
                setEnabled(true);
            }
        }));

        hud(new TextHudElement("script." + manifest.id(), manifest.name(), Anchor.CENTER_LEFT, 4, 0) {
            @Override protected boolean shouldRender() { return !hudLines.isEmpty(); }

            @Override
            protected List<String> lines(boolean preview) {
                return hudLines.isEmpty() && preview ? List.of(manifest.name()) : hudLines;
            }
        });

        on(Events.ClientTick.class, e -> guarded(() -> {
            runtime.onTick();
            hudLines = runtime.hudLines();
        }));
        on(Events.ChatMessage.class, e -> guarded(() -> runtime.onChat(e.text())));
        on(Events.HitConfirmed.class, e -> guarded(() -> runtime.onHit(e.distance(), e.critical(), e.targetIsPlayer())));
    }

    public ScriptManifest manifest() { return manifest; }

    public String status() { return status; }

    private String source() throws IOException {
        return Files.readString(folder.resolve(manifest.main()), StandardCharsets.UTF_8);
    }

    private String hash() throws IOException {
        return ScriptGrants.hash(Files.readString(folder.resolve("script.json"), StandardCharsets.UTF_8), source());
    }

    private String about() {
        String perms = manifest.permissions().isEmpty() ? Tr.of("cobalmoon.script.no_permissions")
                : manifest.permissions().stream().map(p -> Tr.of(p.translationKey())).collect(Collectors.joining(", "));
        StringBuilder sb = new StringBuilder();
        sb.append(Tr.of("cobalmoon.script.by", manifest.version(), manifest.author())).append(' ');
        sb.append(Tr.of("cobalmoon.script.asks", perms));
        if (!manifest.unknownPermissions().isEmpty()) {
            sb.append(' ').append(Tr.of("cobalmoon.script.unknown_permissions", String.join(", ", manifest.unknownPermissions())));
        }
        if (!status.isEmpty()) sb.append(' ').append(status);
        return sb.toString();
    }

    /** Grants exactly the permissions in the manifest (for this version of the files) and turns the script on. */
    public void grantAndEnable() {
        try {
            grants.grant(manifest.id(), hash(), manifest.permissions());
            status = "";
            setEnabled(true);
        } catch (IOException e) {
            status = Tr.of("cobalmoon.script.error", e.getMessage());
        }
    }

    @Override
    protected void onEnable() {
        String hash;
        String src;
        try {
            src = source();
            hash = hash();
        } catch (IOException e) {
            status = Tr.of("cobalmoon.script.error", e.getMessage());
            throw new IllegalStateException(status, e);
        }
        if (!grants.isGranted(manifest.id(), hash, manifest.permissions())) {
            status = Tr.of("cobalmoon.script.needs_grant");
            throw new NotGranted(manifest.id());
        }
        try {
            runtime = new ScriptRuntime(manifest.id() + "/" + manifest.main(), src, manifest.permissions(), new GameHost());
            status = Tr.of("cobalmoon.script.running");
        } catch (ScriptRuntime.ScriptException e) {
            status = Tr.of("cobalmoon.script.error", e.getMessage());
            throw new IllegalStateException(status);
        }
    }

    @Override
    protected void onDisable() {
        runtime = null;
        hudLines = List.of();
    }

    private interface ScriptCall {
        void run() throws ScriptRuntime.ScriptException;
    }

    /** Runs a callback; a script error turns the script off and is shown in its settings. */
    private void guarded(ScriptCall call) {
        if (runtime == null) return;
        try {
            call.run();
        } catch (ScriptRuntime.ScriptException e) {
            status = Tr.of("cobalmoon.script.error", e.getMessage());
            CobalmoonClient.LOG.warn("Script '{}' stopped: {}", manifest.id(), e.getMessage());
            setEnabled(false);
        }
    }

    /** What the game exposes to the script (each call is already permission-checked by the runtime). */
    private final class GameHost implements ScriptRuntime.Host {
        @Override
        public void log(String message) {
            CobalmoonClient.LOG.info("[script {}] {}", manifest.id(), message);
        }

        @Override
        public void sendChat(String message) {
            var c = Minecraft.getInstance().getConnection();
            if (c != null) c.sendChat(message);
        }

        @Override
        public Map<String, Object> player() {
            Minecraft mc = Minecraft.getInstance();
            Player p = mc.player;
            Map<String, Object> m = new LinkedHashMap<>();
            if (p != null) {
                m.put("x", p.getX());
                m.put("y", p.getY());
                m.put("z", p.getZ());
                m.put("yaw", p.getYRot());
                m.put("health", p.getHealth());
            }
            m.put("fps", mc.getFps());
            m.put("cps", Clicks.LEFT.cps());
            return m;
        }

        @Override
        public Map<String, Object> loadStore() {
            try {
                if (!Files.isRegularFile(dataFile)) return null;
                return new Gson().fromJson(Files.readString(dataFile, StandardCharsets.UTF_8), new TypeToken<LinkedHashMap<String, Object>>() {}.getType());
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void saveStore(Map<String, Object> data) {
            try {
                Files.createDirectories(dataFile.getParent());
                Files.writeString(dataFile, new Gson().toJson(data), StandardCharsets.UTF_8);
            } catch (IOException e) {
                CobalmoonClient.LOG.warn("Could not save data of script '{}'", manifest.id(), e);
            }
        }
    }
}
