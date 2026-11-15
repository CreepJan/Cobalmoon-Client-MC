/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core;

import dev.cobalmoon.api.Cobalmoon;
import dev.cobalmoon.api.event.EventBus;
import dev.cobalmoon.api.event.Events;
import dev.cobalmoon.api.hud.HudElement;
import dev.cobalmoon.core.combat.CombatTracker;
import dev.cobalmoon.core.compat.Platform;
import dev.cobalmoon.core.modules.CombatHudModules;
import dev.cobalmoon.core.modules.GameplayModules;
import dev.cobalmoon.core.modules.HitColorModule;
import dev.cobalmoon.core.modules.SafeServerModule;
import dev.cobalmoon.core.modules.StatusHudModules;
import dev.cobalmoon.core.modules.VisualModules;
import dev.cobalmoon.core.modules.ZoomModule;
import java.util.ArrayList;
import java.util.List;
import dev.cobalmoon.core.compat.TitleBranding;
import dev.cobalmoon.core.config.ConfigStore;
import dev.cobalmoon.core.editor.HudEditor;
import dev.cobalmoon.core.input.Clicks;
import dev.cobalmoon.core.module.ModuleManager;
import dev.cobalmoon.core.modules.CoordinatesModule;
import dev.cobalmoon.core.modules.CpsModule;
import dev.cobalmoon.core.modules.CrosshairModule;
import dev.cobalmoon.core.modules.FpsModule;
import dev.cobalmoon.core.modules.KeystrokesModule;
import dev.cobalmoon.core.render.HudRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Fabric client entrypoint of the Cobalmoon core. */
public final class CobalmoonClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("Cobalmoon");
    private static final int GLFW_KEY_RIGHT_SHIFT = 344;
    private static final int GLFW_KEY_C = 67;
    private static final int GLFW_KEY_UNKNOWN = -1;

    private static ConfigStore store;
    private static CombatTracker combat;
    private static ClientSettings settings;
    private static ModuleManager modules;
    private static CrosshairModule crosshair;
    private static final Set<String> reportedHudErrors = ConcurrentHashMap.newKeySet();
    private static int ticks;

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        store = new ConfigStore(loader.getConfigDir().resolve("cobalmoon"));
        EventBus bus = new EventBus((event, error) ->
                LOG.error("Handler for {} failed", event.getClass().getSimpleName(), error));
        dev.cobalmoon.api.module.Module.setErrorHandler((m, t) ->
                LOG.error("Module '{}' failed and was turned off; the game keeps running", m.id(), t));
        settings = new ClientSettings(store);
        modules = new ModuleManager(bus, store);
        CoreApi api = new CoreApi(CoreApi.clientVersion(loader), CoreApi.minecraftVersion(loader), modules, bus);
        Cobalmoon.install(api);
        LOG.info("{} v{} starting on Minecraft {}", Branding.NAME, api.clientVersion(), api.minecraftVersion());

        Clicks.install(bus);
        combat = new CombatTracker(bus);

        // HUD
        modules.register(new FpsModule());
        modules.register(new CpsModule());
        modules.register(new KeystrokesModule());
        modules.register(new CoordinatesModule());
        modules.register(new CombatHudModules.Ping());
        modules.register(new CombatHudModules.Combo());
        modules.register(new CombatHudModules.Reach());
        modules.register(new CombatHudModules.Crits());
        modules.register(new StatusHudModules.Armor());
        modules.register(new StatusHudModules.Potions());
        modules.register(new StatusHudModules.Cooldown());
        modules.register(new StatusHudModules.Timers());
        // Visual
        crosshair = modules.register(new CrosshairModule());
        ZoomModule zoom = modules.register(new ZoomModule());
        modules.register(new HitColorModule());
        modules.register(new VisualModules.LowFire());
        modules.register(new VisualModules.LowShield());
        modules.register(new VisualModules.NoFog());
        modules.register(new VisualModules.Particles());
        modules.register(new VisualModules.Hitsounds());
        // Gameplay
        modules.register(new GameplayModules.ToggleSprint());
        GameplayModules.HotbarBinds hotbar = modules.register(new GameplayModules.HotbarBinds());
        modules.register(new GameplayModules.AutoMessages());
        // Other
        modules.register(new SafeServerModule(() -> modules, () -> combat.server(), store.root()));

        zoom.bindKey(Platform.registerKey("key.cobalmoon.zoom", GLFW_KEY_C));
        List<KeyMapping> slotKeys = new ArrayList<>();
        for (GameplayModules.HotbarBinds.Kind k : GameplayModules.HotbarBinds.Kind.values()) {
            slotKeys.add(Platform.registerKey(GameplayModules.HotbarBinds.keyName(k), GLFW_KEY_UNKNOWN));
        }
        hotbar.bindKeys(slotKeys);

        TitleBranding.register(api);
        Platform.registerHud(c -> {
            if (Platform.isEditorOpen()) return;
            for (HudElement e : modules.hudElements()) {
                if (e.isVisible()) HudRenderer.render(e, c, false, t -> reportHudError(e, t));
            }
        });
        Platform.registerCrosshair(c -> {
            if (!crosshair.isEnabled()) return false; // vanilla crosshair
            if (!Platform.isEditorOpen() && Platform.firstPerson()) {
                crosshair.draw(c, c.screenWidth() / 2, c.screenHeight() / 2);
            }
            return true;
        });

        KeyMapping editorKey = Platform.registerKey("key.cobalmoon.editor", GLFW_KEY_RIGHT_SHIFT);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            combat.tick(client);
            bus.post(Events.ClientTick.INSTANCE);
            while (editorKey.consumeClick()) {
                if (!Platform.isScreenOpen()) openEditor();
            }
            if (++ticks % 20 == 0) flushConfig();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> flushConfig());

        if (!store.failedReads().isEmpty()) {
            LOG.warn("Could not read config {}; defaults used, broken files kept as .bak", store.failedReads());
        }

        AddonLoader.Result addons = AddonLoader.run(loader, api);
        LOG.info("Cobalmoon ready: {} modules, {} addon(s) initialised, {} failed",
                modules.all().size(), addons.loaded(), addons.failed());
    }

    private static void reportHudError(HudElement e, Throwable t) {
        if (reportedHudErrors.add(e.id())) LOG.error("HUD element '{}' failed to render (further errors hidden)", e.id(), t);
    }

    public static void openEditor() {
        Platform.openEditor(new HudEditor(modules, settings, crosshair));
    }

    /** Combat event source; null before initialisation. */
    public static CombatTracker combat() {
        return combat;
    }

    public static boolean reduceMotion() {
        return settings != null && settings.reduceMotion.get();
    }

    public static ModuleManager modules() {
        return modules;
    }

    public static void flushConfig() {
        if (store == null || !store.hasPending()) return;
        try {
            store.flush();
        } catch (IOException e) {
            LOG.error("Could not save Cobalmoon config", e);
        }
    }
}
