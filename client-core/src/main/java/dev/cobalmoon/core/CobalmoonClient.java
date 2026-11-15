/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core;

import dev.cobalmoon.api.Cobalmoon;
import dev.cobalmoon.api.event.EventBus;
import dev.cobalmoon.api.event.Events;
import dev.cobalmoon.api.hud.HudElement;
import dev.cobalmoon.core.compat.Platform;
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

    private static ConfigStore store;
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
        settings = new ClientSettings(store);
        modules = new ModuleManager(bus, store);
        CoreApi api = new CoreApi(CoreApi.clientVersion(loader), CoreApi.minecraftVersion(loader), modules, bus);
        Cobalmoon.install(api);
        LOG.info("{} v{} starting on Minecraft {}", Branding.NAME, api.clientVersion(), api.minecraftVersion());

        Clicks.install(bus);
        modules.register(new FpsModule());
        modules.register(new CpsModule());
        modules.register(new KeystrokesModule());
        modules.register(new CoordinatesModule());
        crosshair = modules.register(new CrosshairModule());

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
