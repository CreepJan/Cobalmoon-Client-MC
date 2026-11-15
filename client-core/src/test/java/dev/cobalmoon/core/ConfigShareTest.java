package dev.cobalmoon.core;

import dev.cobalmoon.api.event.EventBus;
import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.TextHudElement;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.BooleanSetting;
import dev.cobalmoon.api.setting.NumberSetting;
import dev.cobalmoon.core.config.ConfigStore;
import dev.cobalmoon.core.module.ModuleManager;
import dev.cobalmoon.core.share.ConfigShare;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

class ConfigShareTest {
    @TempDir Path dir;

    static final class M extends Module {
        final BooleanSetting flag = setting(new BooleanSetting("flag", "Flag", false));
        final NumberSetting size = setting(new NumberSetting("size", "Size", 4, 1, 10, 1));
        final TextHudElement text;

        M(String id) {
            super(id, id, id, Category.HUD);
            text = hud(new TextHudElement(id, id, Anchor.TOP_LEFT, 4, 4) {
                @Override protected List<String> lines(boolean preview) { return List.of("x"); }
            });
        }
    }

    private ModuleManager manager(Path p, M... ms) {
        ModuleManager mm = new ModuleManager(new EventBus((e, t) -> fail(t)), new ConfigStore(p));
        for (M m : ms) mm.register(m);
        return mm;
    }

    @Test
    void roundTripCopiesSettingsLayoutAndState() {
        M a = new M("a");
        ModuleManager src = manager(dir.resolve("src"), a);
        a.setEnabled(true);
        a.flag.set(true);
        a.size.set(8.0);
        a.text.layout().moveTo(200, 120, 20, 10, 480, 270);
        a.text.layout().setScale(1.5f);
        String code = ConfigShare.encode(ConfigShare.collect(src.all(), ConfigShare.Scope.ALL, Set.of()));
        assertTrue(code.startsWith("CBM1:"));
        assertTrue(code.length() < 600, "codes stay short: " + code.length());

        M b = new M("a");
        ModuleManager dst = manager(dir.resolve("dst"), b);
        var r = ConfigShare.apply(dst, ConfigShare.decode(code), Set.of());
        assertEquals(1, r.applied());
        assertTrue(b.isEnabled());
        assertTrue(b.flag.get());
        assertEquals(8.0, b.size.get());
        assertEquals(a.text.layout().toJson(), b.text.layout().toJson());
        assertTrue(dst.store().hasPending(), "imported state is saved");
    }

    @Test
    void hudScopeLeavesSettingsAlone() {
        M a = new M("a");
        ModuleManager src = manager(dir.resolve("s"), a);
        a.flag.set(true);
        a.text.layout().setScale(2f);
        String code = ConfigShare.encode(ConfigShare.collect(src.all(), ConfigShare.Scope.HUD, Set.of()));
        M b = new M("a");
        ModuleManager dst = manager(dir.resolve("d"), b);
        ConfigShare.apply(dst, ConfigShare.decode(code), Set.of());
        assertEquals(2f, b.text.layout().scale);
        assertFalse(b.flag.get(), "settings are not part of a HUD-only code");
    }

    @Test
    void unknownModulesAndExclusionsAreReported() {
        M a = new M("a"), extra = new M("addon.extra");
        String code = ConfigShare.encode(ConfigShare.collect(manager(dir.resolve("s"), a, extra).all(), ConfigShare.Scope.ALL, Set.of()));
        M b = new M("a");
        var r = ConfigShare.apply(manager(dir.resolve("d"), b), ConfigShare.decode(code), Set.of("a"));
        assertEquals(0, r.applied(), "excluded");
        assertEquals(List.of("addon.extra"), r.unknownModules());
    }

    @Test
    void badCodesGiveReadableErrors() {
        assertThrows(IllegalArgumentException.class, () -> ConfigShare.decode("hello"));
        assertThrows(IllegalArgumentException.class, () -> ConfigShare.decode("CBM1:%%%"));
        assertThrows(IllegalArgumentException.class, () -> ConfigShare.decode("CBM1:AAAA"));
        // Whitespace and line breaks from chat/Discord are ignored.
        M a = new M("a");
        String code = ConfigShare.encode(ConfigShare.collect(manager(dir.resolve("w"), a).all(), ConfigShare.Scope.ALL, Set.of()));
        String split = code.substring(0, 10) + "\n " + code.substring(10);
        assertNotNull(ConfigShare.decode(split));
    }

    @Test
    void decompressionBombIsRejected() {
        byte[] zeros = new byte[ConfigShare_MAX + 1024];
        Deflater d = new Deflater();
        d.setInput(zeros);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        String bomb = "CBM1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
        var e = assertThrows(IllegalArgumentException.class, () -> ConfigShare.decode(bomb));
        assertTrue(e.getMessage().contains("too much"));
    }

    static final int ConfigShare_MAX = 4 * 1024 * 1024;

    @Test
    void codesContainNothingPersonal() {
        M a = new M("a");
        Map<String, Object> data = ConfigShare.collect(manager(dir.resolve("p"), a).all(), ConfigShare.Scope.ALL, Set.of());
        String json = new com.google.gson.Gson().toJson(data);
        assertFalse(json.contains(dir.toString().replace("\\", "\\\\")), "no file paths");
        assertEquals(Set.of("v", "scope", "modules"), data.keySet());
    }
}
