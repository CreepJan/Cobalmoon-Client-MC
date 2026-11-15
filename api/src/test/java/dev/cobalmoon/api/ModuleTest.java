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

    @Test
    void idsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new Module("Bad Id", "x", "x", Category.OTHER) {});
    }
}
