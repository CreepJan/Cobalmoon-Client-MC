/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.cobalmoon.api.event.Events;
import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.HudLayout;
import dev.cobalmoon.api.hud.TextHudElement;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.ActionSetting;
import dev.cobalmoon.api.setting.BooleanSetting;
import dev.cobalmoon.api.theme.DefaultTokens;
import dev.cobalmoon.core.CobalmoonClient;
import dev.cobalmoon.core.module.ModuleManager;
import dev.cobalmoon.core.share.ConfigShare;
import dev.cobalmoon.core.ui.Tr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Per-server profiles: a saved set of module settings and HUD layout that loads automatically when
 * you join that server (or one of its subdomains). Leaving saves your changes into the profile and
 * restores your normal configuration. Profiles live in {@code config/cobalmoon/profiles/<host>.json}.
 */
public final class ServerProfilesModule extends Module {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Never part of a profile: switching profiles must not switch this module itself off. */
    private static final Set<String> EXCLUDED = ConfigShare.NOT_SHARED;

    private final BooleanSetting restore = setting(new BooleanSetting("restore", "cobalmoon.setting.profiles.restore", true));
    private final Supplier<ModuleManager> modules;
    private final Supplier<String> currentServer;
    private final Path dir;
    private Map<String, Object> home;
    private String activeHost;
    private String notice = "";
    private long noticeUntil;

    public ServerProfilesModule(Supplier<ModuleManager> modules, Supplier<String> currentServer, Path configDir) {
        super("server_profiles", "cobalmoon.module.server_profiles", "cobalmoon.module.server_profiles.desc", Category.OTHER);
        this.modules = modules;
        this.currentServer = currentServer;
        this.dir = configDir.resolve("profiles");
        setting(new ActionSetting("save_current", "cobalmoon.setting.profiles.save_current", this::saveForCurrentServer));
        setting(new ActionSetting("delete_current", "cobalmoon.setting.profiles.delete_current", this::deleteForCurrentServer));

        HudLayout l = new HudLayout(Anchor.TOP_CENTER, 0, 24);
        l.backgroundColor = (0xE6 << 24) | (DefaultTokens.NIGHT_RAISED & 0xFFFFFF);
        hud(new TextHudElement("profile_notice", "cobalmoon.module.server_profiles", l) {
            @Override protected boolean shouldRender() { return !notice.isEmpty() && System.nanoTime() < noticeUntil; }

            @Override
            protected List<String> lines(boolean preview) {
                return List.of(labeled(Tr.of("cobalmoon.profiles.label"), notice.isEmpty() && preview ? "example.net" : notice));
            }
        });
        on(Events.ServerJoin.class, e -> onJoin(SafeServerModule.hostOf(e.address())));
        on(Events.ServerLeave.class, e -> onLeave());
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    // ---- files --------------------------------------------------------------------------------

    /** Safe file name for a host ("play.example.net" stays as is; anything odd becomes "_"). */
    public static String fileName(String host) {
        return host.replaceAll("[^a-z0-9.-]", "_") + ".json";
    }

    /** Profile host for a server: the exact host or the closest parent domain that has a profile. */
    public static String findProfileHost(String host, Set<String> profileHosts) {
        if (host == null) return null;
        String h = host;
        while (true) {
            if (profileHosts.contains(h)) return h;
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return null; // stop at "example.net"
            h = h.substring(dot + 1);
        }
    }

    public Set<String> profileHosts() {
        Set<String> out = new java.util.TreeSet<>();
        try (var files = Files.list(dir)) {
            files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .forEach(n -> out.add(n.substring(0, n.length() - 5)));
        } catch (IOException ignored) {
            // no profiles yet
        }
        return out;
    }

    private Map<String, Object> read(String host) {
        try {
            return GSON.fromJson(Files.readString(dir.resolve(fileName(host)), StandardCharsets.UTF_8),
                    new TypeToken<LinkedHashMap<String, Object>>() {}.getType());
        } catch (Exception e) {
            CobalmoonClient.LOG.warn("Could not read server profile {}", host, e);
            return null;
        }
    }

    private void write(String host, Map<String, Object> data) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName(host)), GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            CobalmoonClient.LOG.error("Could not save server profile {}", host, e);
        }
    }

    private Map<String, Object> current() {
        return ConfigShare.collect(modules.get().all(), ConfigShare.Scope.ALL, EXCLUDED);
    }

    private void show(String text) {
        notice = text;
        noticeUntil = System.nanoTime() + 4_000_000_000L;
    }

    // ---- switching ----------------------------------------------------------------------------

    private void onJoin(String host) {
        activeHost = null;
        String profile = findProfileHost(host, profileHosts());
        if (profile == null) return;
        Map<String, Object> data = read(profile);
        if (data == null || !(data.get("modules") instanceof Map)) return;
        home = current();
        ConfigShare.apply(modules.get(), data, EXCLUDED);
        activeHost = profile;
        show(Tr.of("cobalmoon.profiles.loaded", profile));
    }

    private void onLeave() {
        if (activeHost == null) return;
        write(activeHost, current()); // keep what you changed while playing there
        if (restore.get() && home != null) ConfigShare.apply(modules.get(), home, EXCLUDED);
        activeHost = null;
        home = null;
        CobalmoonClient.flushConfig();
    }

    private void saveForCurrentServer() {
        String host = SafeServerModule.hostOf(currentServer.get());
        if (host == null) {
            show(Tr.of("cobalmoon.profiles.no_server"));
            return;
        }
        if (activeHost == null) home = currentHomeOrNow();
        write(host, current());
        activeHost = host;
        show(Tr.of("cobalmoon.profiles.saved", host));
    }

    private Map<String, Object> currentHomeOrNow() {
        return home != null ? home : current();
    }

    private void deleteForCurrentServer() {
        String host = activeHost != null ? activeHost : SafeServerModule.hostOf(currentServer.get());
        if (host == null) {
            show(Tr.of("cobalmoon.profiles.no_server"));
            return;
        }
        try {
            Files.deleteIfExists(dir.resolve(fileName(host)));
        } catch (IOException e) {
            CobalmoonClient.LOG.error("Could not delete server profile {}", host, e);
        }
        if (host.equals(activeHost)) {
            activeHost = null; // keep the current settings as your normal ones
            home = null;
        }
        show(Tr.of("cobalmoon.profiles.deleted", host));
    }

    /** Test hooks. */
    public String activeHost() { return activeHost; }

    public void simulateJoin(String address) { onJoin(SafeServerModule.hostOf(address)); }

    public void simulateLeave() { onLeave(); }

    public void saveFor(String host, Map<String, Object> data) { write(host, data); }

    public List<String> profiles() { return new ArrayList<>(profileHosts()); }
}
