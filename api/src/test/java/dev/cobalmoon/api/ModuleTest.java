package dev.cobalmoon.api;

import dev.cobalmoon.api.event.EventBus;
import dev.cobalmoon.api.event.Events;
import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.TextHudElement;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.BooleanSetting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleTest {

    static final class Counter extends Module {
        int ticks;
        int enables;
        int disables;
        final BooleanSetting flag = setting(new BooleanSetting("flag", "Flag", true));
        final TextHudElement text = hud(new TextHudElement("counter", "Counter", Anchor.TOP_LEFT, 2, 2) {
            @Override protected List<String> lines(boolean preview) { return List.of(labeled("Ticks", "" + ticks)); }
        });

        Counter() {
            super("test:counter", "Counter", "Counts ticks", Category.OTHER);
            on(Events.ClientTick.class, e -> ticks++);
        }

        @Override protected void onEnable() { enables++; }
        @Override protected void onDisable() { disables++; }
    }

    @Test
    void handlersOnlyRunWhileEnabled() {
        EventBus bus = new EventBus((e, t) -> fail(t));
        Counter m = new Counter();
        m.bind(bus);
        bus.post(Events.ClientTick.INSTANCE);
        assertEquals(0, m.ticks);

        m.setEnabled(true);
        bus.post(Events.ClientTick.INSTANCE);
        bus.post(Events.ClientTick.INSTANCE);
        assertEquals(2, m.ticks);
        assertEquals(1, m.enables);

        m.setEnabled(false);
        bus.post(Events.ClientTick.INSTANCE);
        assertEquals(2, m.ticks);
        assertEquals(1, m.disables);
        assertFalse(bus.hasListeners(Events.ClientTick.class));
    }

    @Test
    void enabledBeforeBindActivatesOnBind() {
        EventBus bus = new EventBus((e, t) -> fail(t));
        Counter m = new Counter();
        m.setEnabled(true);
        assertEquals(0, m.enables, "no bus yet: hooks wait for bind");
        m.bind(bus);
        assertEquals(1, m.enables);
        bus.post(Events.ClientTick.INSTANCE);
        assertEquals(1, m.ticks);
        assertThrows(IllegalStateException.class, () -> m.bind(bus));
    }

    @Test
    void hudVisibilityFollowsOwner() {
        Counter m = new Counter();
        assertSame(m, m.text.owner());
        assertFalse(m.text.isVisible());
        m.setEnabled(true);
        assertTrue(m.text.isVisible());
        assertSame(m.flag, m.setting("flag"));
    }

    @Test
    void toggleListenersFire() {
        Counter m = new Counter();
        List<Boolean> seen = new ArrayList<>();
        m.onToggle(x -> seen.add(x.isEnabled()));
        m.toggle();
        m.toggle();
        assertEquals(List.of(true, false), seen);
    }

    @Test
    void badHandlerDoesNotStopOthers() {
        List<Throwable> errors = new ArrayList<>();
        EventBus bus = new EventBus((e, t) -> errors.add(t));
        int[] calls = {0};
        bus.subscribe(Events.ClientTick.class, e -> { throw new RuntimeException("boom"); });
        bus.subscribe(Events.ClientTick.class, e -> calls[0]++);
        bus.post(Events.ClientTick.INSTANCE);
        assertEquals(1, calls[0]);
        assertEquals(1, errors.size());
    }

    /** A module whose onEnable fails (e.g. game not ready yet). */
    static final class Broken extends Module {
        int ticks;
        Broken() {
            super("test:broken", "Broken", "Throws on enable", Category.OTHER);
            on(Events.ClientTick.class, e -> ticks++);
        }
        @Override protected void onEnable() { throw new NullPointerException("options is null"); }
        @Override protected void onDisable() { throw new IllegalStateException("also broken"); }
    }

    @Test
    void failingModulesNeverCrashAndTurnThemselvesOff() {
        List<String> reported = new ArrayList<>();
        Module.setErrorHandler((m, t) -> reported.add(m.id() + ": " + t.getMessage()));
        try {
            EventBus bus = new EventBus((e, t) -> fail(t));
            // Saved as enabled, activated during startup (the crash reported by a player).
            Broken atStartup = new Broken();
            atStartup.setEnabled(true);
            assertDoesNotThrow(() -> atStartup.bind(bus));
            assertFalse(atStartup.isEnabled(), "turned back off");
            bus.post(Events.ClientTick.INSTANCE);
            assertEquals(0, atStartup.ticks, "its handlers were removed");

            // Toggled from the menu.
            Broken fromMenu = new Broken();
            fromMenu.bind(bus);
            assertDoesNotThrow(() -> fromMenu.setEnabled(true));
            assertFalse(fromMenu.isEnabled());
            assertEquals(List.of("test:broken: options is null", "test:broken: options is null"), reported);
        } finally {
            Module.setErrorHandler((m, t) -> t.printStackTrace());
        }
    }

    @Test
    void idsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new Module("Bad Id", "x", "x", Category.OTHER) {});
    }
}
