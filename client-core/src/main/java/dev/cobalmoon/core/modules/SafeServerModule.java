/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.modules;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.cobalmoon.api.event.Events;
import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.HudLayout;
import dev.cobalmoon.api.hud.TextHudElement;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.module.ModuleRegistry;
import dev.cobalmoon.api.setting.ActionSetting;
import dev.cobalmoon.api.setting.NumberSetting;
import dev.cobalmoon.api.setting.StringSetting;
import dev.cobalmoon.api.theme.DefaultTokens;
import dev.cobalmoon.core.CobalmoonClient;
import dev.cobalmoon.core.ui.Tr;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Safe Server Mode: OFF by default and purely informative. When you join a server listed in the
 * community guide, it shows a short, dismissable notice naming the enabled modules that server is
 * known to restrict. It never disables or blocks anything — what you run is your decision.
 *
 * <p>Guide: bundled {@code assets/cobalmoon/safe-server-guide.json}, extended or overridden by
 * {@code config/cobalmoon/safe-server-guide.json}.
 */
public final class SafeServerModule extends Module {
    /** One server entry of the guide. */
    public record Entry(String name, List<String> hosts, Map<String, String> restricted, String source) {}

    private record GuideFile(List<Entry> servers) {}

    private final NumberSetting seconds = setting(new NumberSetting("seconds", "cobalmoon.setting.safe.seconds", 12, 4, 60, 1));
    private final StringSetting dismissed = setting(new StringSetting("dismissed", "dismissed", "", 4000));
    private final Supplier<ModuleRegistry> modules;
    private final Supplier<String> currentServer;
    private List<Entry> guide = List.of();
    private List<String> notice = List.of();
    private long noticeUntil;

    public SafeServerModule(Supplier<ModuleRegistry> modules, Supplier<String> currentServer, Path configDir) {
        super("safe_server", "cobalmoon.module.safe_server", "cobalmoon.module.safe_server.desc", Category.OTHER);
        this.modules = modules;
        this.currentServer = currentServer;
        dismissed.setHidden(true);
        setting(new ActionSetting("dismiss_current", "cobalmoon.setting.safe.dismiss_current", () -> {
            String host = hostOf(currentServer.get());
            if (host != null) dismissed.set(addToList(dismissed.get(), host));
            notice = List.of();
        }));
        setting(new ActionSetting("reset", "cobalmoon.setting.safe.reset", () -> dismissed.set("")));

        guide = loadGuide(configDir);
        HudLayout l = new HudLayout(Anchor.TOP_CENTER, 0, 24);
        l.backgroundColor = (0xE6 << 24) | (DefaultTokens.NIGHT_RAISED & 0xFFFFFF);
        l.accentColor = DefaultTokens.WARNING;
        hud(new TextHudElement("safe_notice", "cobalmoon.module.safe_server", l) {
            @Override
            protected boolean shouldRender() {
                return !notice.isEmpty() && System.nanoTime() < noticeUntil;
            }

            @Override
            protected List<String> lines(boolean preview) {
                if (!notice.isEmpty() || !preview) return notice;
                return List.of(labeled(Tr.of("cobalmoon.safe.title"), "example.net"), Tr.of("cobalmoon.safe.line", "Zoom"));
            }
        });
        on(Events.ServerJoin.class, e -> onJoin(e.address()));
        on(Events.ServerLeave.class, e -> notice = List.of());
    }

    // ---- guide --------------------------------------------------------------------------------

    public static List<Entry> parse(String json) {
        try {
            GuideFile f = new Gson().fromJson(json, GuideFile.class);
            if (f == null || f.servers() == null) return List.of();
            return f.servers().stream().filter(e -> e != null && e.hosts() != null && e.restricted() != null).toList();
        } catch (JsonParseException e) {
            CobalmoonClient.LOG.warn("Safe Server guide is not valid JSON; ignoring it ({})", e.getMessage());
            return List.of();
        }
    }

    static List<Entry> loadGuide(Path configDir) {
        List<Entry> out = new ArrayList<>();
        Path user = configDir.resolve("safe-server-guide.json");
        try {
            if (Files.isRegularFile(user)) out.addAll(parse(Files.readString(user, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            CobalmoonClient.LOG.warn("Could not read {}", user, e);
        }
        try (InputStream in = SafeServerModule.class.getResourceAsStream("/assets/cobalmoon/safe-server-guide.json")) {
            if (in != null) out.addAll(parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            CobalmoonClient.LOG.warn("Could not read the bundled Safe Server guide", e);
        }
        return out;
    }

    /** "Play.Example.net:25565" → "play.example.net". */
    public static String hostOf(String address) {
        if (address == null || address.isBlank()) return null;
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("[")) return a.substring(1, Math.max(1, a.indexOf(']')));
        int colon = a.lastIndexOf(':');
        if (colon > 0 && a.indexOf(':') == colon) a = a.substring(0, colon);
        return a.endsWith(".") ? a.substring(0, a.length() - 1) : a;
    }

    /** Host equals the pattern or is a subdomain of it. */
    public static boolean hostMatches(String host, String pattern) {
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        return !p.isEmpty() && (host.equals(p) || host.endsWith("." + p));
    }

    public static Entry find(List<Entry> guide, String host) {
        if (host == null) return null;
        for (Entry e : guide) for (String h : e.hosts()) if (hostMatches(host, h)) return e;
        return null;
    }

    /** Restricted module ids (from the entry) that are currently enabled, in guide order. */
    public static List<String> restrictedEnabled(Entry entry, Set<String> enabled) {
        return entry.restricted().keySet().stream().filter(enabled::contains).toList();
    }

    static String addToList(String list, String item) {
        Set<String> set = new LinkedHashSet<>(Arrays.asList(list.split(",")));
        set.remove("");
        set.add(item);
        return String.join(",", set);
    }

    // ---- notice -------------------------------------------------------------------------------

    private void onJoin(String address) {
        notice = List.of();
        String host = hostOf(address);
        if (host == null || Arrays.asList(dismissed.get().split(",")).contains(host)) return;
        Entry entry = find(guide, host);
        if (entry == null) return;
        Set<String> enabled = new LinkedHashSet<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Module m : modules.get().all()) {
            if (m.isEnabled()) enabled.add(m.id());
            names.put(m.id(), Tr.of(m.name()));
        }
        List<String> hits = restrictedEnabled(entry, enabled);
        if (hits.isEmpty()) return;
        List<String> lines = new ArrayList<>();
        lines.add(Tr.of("cobalmoon.safe.title") + TextHudElement.LABEL_SEPARATOR + entry.name());
        for (String id : hits) {
            String why = entry.restricted().get(id);
            lines.add(Tr.of("cobalmoon.safe.line", names.getOrDefault(id, id)) + (why == null || why.isBlank() ? "" : " — " + why));
        }
        lines.add(Tr.of("cobalmoon.safe.footer"));
        notice = lines;
        noticeUntil = System.nanoTime() + (long) (seconds.get() * 1e9);
    }

    public List<Entry> guide() { return guide; }
}
