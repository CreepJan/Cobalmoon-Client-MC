package dev.cobalmoon.core;

import dev.cobalmoon.api.event.EventBus;
import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.TextHudElement;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.BooleanSetting;
import dev.cobalmoon.api.setting.ColorSetting;
import dev.cobalmoon.api.setting.NumberSetting;
import dev.cobalmoon.core.config.ConfigStore;
import dev.cobalmoon.core.module.ModuleManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleManagerTest {
    @TempDir Path dir;

    static final class Sample extends Module {
        final BooleanSetting flag = setting(new BooleanSetting("flag", "Flag", false));
        final NumberSetting size = setting(new NumberSetting("size", "Size", 4, 1, 10, 1));
        final ColorSetting color = setting(new ColorSetting("color", "Color", 0xFF52E0D0));
        final TextHudElement text;
        final boolean defaultOn;

        Sample(String id, String hudId, boolean defaultOn) {
            super(id, id, id, Category.HUD);
            this.defaultOn = defaultOn;
            text = hud(new TextHudElement(hudId, "Sample", Anchor.TOP_LEFT, 4, 4) {
                @Override protected List<String> lines(boolean preview) { return List.of("x"); }
            });
        }

        Sample(String id, boolean defaultOn) { this(id, id, defaultOn); }

        Sample(boolean defaultOn) { this("sample", defaultOn); }

        @Override public boolean enabledByDefault() { return defaultOn; }
    }

    private static EventBus bus() {
        return new EventBus((e, t) -> fail(t));
    }

    @Test
    void firstRunUsesDefaults() {
        ModuleManager mm = new ModuleManager(bus(), new ConfigStore(dir));
        Sample on = mm.register(new Sample("on", true));
        Sample off = mm.register(new Sample("off", false));
        assertTrue(on.isEnabled());
        assertFalse(off.isEnabled());
    }

    @Test
    void stateSurvivesRestart() throws Exception {
        ConfigStore store = new ConfigStore(dir);
        ModuleManager mm = new ModuleManager(bus(), store);
        Sample s = mm.register(new Sample(false));
        s.setEnabled(true);
        s.flag.set(true);
        s.size.set(7.0);
        s.color.set(0x80FF0000);
        s.text.layout().moveTo(300, 200, 20, 10, 480, 270);
        s.text.layout().setScale(1.5f);
        mm.markDirty(s);
        assertTrue(store.hasPending());
        assertEquals(1, store.flush());
        assertTrue(Files.exists(dir.resolve("modules/sample.json")));

        // "Restart": fresh objects, same folder.
        ModuleManager mm2 = new ModuleManager(bus(), new ConfigStore(dir));
        Sample s2 = mm2.register(new Sample(false));
        assertTrue(s2.isEnabled());
        assertTrue(s2.flag.get());
        assertEquals(7.0, s2.size.get());
        assertEquals(0x80FF0000, s2.color.get());
        assertEquals(s.text.layout().toJson(), s2.text.layout().toJson());
    }

    @Test
    void togglesAndSettingChangesMarkDirty() throws Exception {
        ConfigStore store = new ConfigStore(dir);
        ModuleManager mm = new ModuleManager(bus(), store);
        Sample s = mm.register(new Sample(false));
        assertFalse(store.hasPending());
        s.toggle();
        assertTrue(store.hasPending());
        store.flush();
        s.size.set(9.0);
        assertTrue(store.hasPending());
    }

    @Test
    void brokenFileFallsBackToDefaultsAndIsKept() throws Exception {
        Files.createDirectories(dir.resolve("modules"));
        Files.writeString(dir.resolve("modules/sample.json"), "{ not json");
        ConfigStore store = new ConfigStore(dir);
        Sample s = new ModuleManager(bus(), store).register(new Sample(true));
        assertTrue(s.isEnabled());
        assertTrue(Files.exists(dir.resolve("modules/sample.json.bak")));
        assertEquals(1, store.failedReads().size());
    }

    @Test
    void namespacedIdsMakeSafeFileNames() {
        ConfigStore store = new ConfigStore(dir);
        assertEquals(dir.resolve("modules/example__ping.json"), store.file("modules/example:ping"));
    }

    @Test
    void duplicateIdsAreRejected() {
        ModuleManager mm = new ModuleManager(bus(), new ConfigStore(dir));
        mm.register(new Sample("a", false));
        assertThrows(IllegalArgumentException.class, () -> mm.register(new Sample("a", false)));
        // different module id, same HUD element id
        assertThrows(IllegalArgumentException.class, () -> mm.register(new Sample("b", "a", false)));
    }
}
