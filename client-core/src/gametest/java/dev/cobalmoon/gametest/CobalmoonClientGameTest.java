/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.gametest;

import dev.cobalmoon.api.Cobalmoon;
import dev.cobalmoon.api.hud.HudElement;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.core.CobalmoonClient;
import dev.cobalmoon.core.compat.Platform;
import dev.cobalmoon.core.editor.HudEditor;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * End-to-end test in the real game: modules load with their defaults, can be toggled hot, the HUD
 * editor opens with its key, dragging an element moves it, and everything is saved to disk.
 * Screenshots land in the run directory's {@code screenshots/} folder.
 */
public final class CobalmoonClientGameTest implements FabricClientGameTest {
    private static final int RIGHT_SHIFT = 344;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // Spanish has the longest UI strings: run the visual checks in it.
        java.util.concurrent.CompletableFuture<?> reload = ctx.computeOnClient(mc -> {
            mc.getLanguageManager().setSelected("es_es");
            return mc.reloadResourcePacks();
        });
        ctx.waitFor(mc -> reload.isDone(), 20 * 60);

        // Compatibility run (-Pcompat): every pinned third-party mod must actually be loaded.
        if (System.getProperty("cobalmoon.compat") != null) {
            List<String> loaded = checkCompatModsLoaded();
            dev.cobalmoon.core.CobalmoonClient.LOG.info("Compatibility test running alongside: {}", loaded);
        }

        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            ctx.waitTicks(40);
            if (System.getProperty("cobalmoon.compat") != null) {
                ctx.takeScreenshot("cobalmoon-compat-world");
            }

            // 1. Defaults: all built-in modules registered; FPS/CPS/keystrokes (and zoom) on, the rest off.
            List<String> ids = ctx.computeOnClient(mc -> Cobalmoon.api().modules().all().stream().map(Module::id).toList());
            check(ids.containsAll(List.of("fps", "cps", "keystrokes", "coordinates", "crosshair", "ping", "combo", "reach", "crits",
                    "armor", "potions", "cooldown", "timers", "zoom", "hit_color", "low_fire", "low_shield", "no_fog", "particles",
                    "hitsounds", "toggle_sprint", "hotbar_binds", "auto_messages", "safe_server")), "modules: " + ids);
            if (Boolean.getBoolean("cobalmoon.test.enableAllModules")) {
                // Every module was enabled during startup: all must still be on (none failed) and the game is running.
                List<String> off = ctx.computeOnClient(mc -> Cobalmoon.api().modules().all().stream()
                        .filter(m -> !m.isEnabled()).map(Module::id).toList());
                check(off.isEmpty(), "modules that failed to start enabled: " + off);
                ctx.takeScreenshot("cobalmoon-all-modules");
                return;
            }
            check(!enabled(ctx, "safe_server") && !enabled(ctx, "auto_messages"), "Safe Server Mode and auto messages are off by default");
            check(enabled(ctx, "fps") && enabled(ctx, "cps") && enabled(ctx, "keystrokes"), "HUD modules on by default");
            check(!enabled(ctx, "coordinates") && !enabled(ctx, "crosshair"), "coordinates/crosshair off by default");

            // 2. Hot toggle + live input: hold W and click so keystrokes/CPS have something to show.
            ctx.runOnClient(mc -> {
                module("coordinates").setEnabled(true);
                module("crosshair").setEnabled(true);
            });
            ctx.getInput().holdKey(o -> o.keyUp);
            for (int i = 0; i < 6; i++) {
                ctx.getInput().pressMouse(0);
                ctx.waitTick();
            }
            ctx.waitTicks(2);
            ctx.takeScreenshot("cobalmoon-hud");
            ctx.getInput().releaseKey(o -> o.keyUp);

            // 3. Editor opens with Right Shift.
            ctx.getInput().pressKey(RIGHT_SHIFT);
            ctx.waitFor(mc -> Platform.currentEditor() != null);
            ctx.waitTicks(10); // let the fade-in finish
            ctx.takeScreenshot("cobalmoon-editor");

            // 4. Drag the FPS element to the middle of the screen through the editor's input handling.
            HudElement fps = module("fps").hudElements().get(0);
            String before = ctx.computeOnClient(mc -> fps.layout().anchor.name());
            // The "Edit HUD" button at the top switches to the clean layout view (no panels).
            ctx.runOnClient(mc -> {
                HudEditor ed = Platform.currentEditor();
                var b = ed.modeButton();
                check(b.w() > 0 && ed.mouseClicked(b.x() + b.w() / 2.0, b.y() + b.h() / 2.0, 0, false) && !ed.panelsVisible(),
                        "Edit HUD button enters the clean layout view");
                int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
                double startX = fps.layout().x(sw, 10) + 4, startY = fps.layout().y(sh, 10) + 4;
                check(ed.mouseClicked(startX, startY, 0, false), "click on FPS element");
                check(ed.selection().contains(fps), "FPS element selected");
                ed.mouseDragged(sw / 2.0 + 30, sh / 2.0 + 25);
                ed.mouseReleased(sw / 2.0 + 30, sh / 2.0 + 25, 0);
            });
            ctx.waitTicks(2);
            String after = ctx.computeOnClient(mc -> fps.layout().anchor.name());
            check(!before.equals(after), "anchor changed after drag: " + before + " -> " + after);

            // Resize with the corner handle: drag it right/down and the element grows.
            float scaleBefore = ctx.computeOnClient(mc -> fps.layout().scale);
            ctx.runOnClient(mc -> {
                HudEditor ed = Platform.currentEditor();
                var b = ed.boundsOf(fps);
                // the handle sits on the bottom-right corner
                check(ed.mouseClicked(b.right() - 1, b.bottom() - 1, 0, false), "press on resize handle");
                ed.mouseDragged(b.x() + b.w() * 2.0, b.y() + b.h() * 2.0);
                ed.mouseReleased(b.x() + b.w() * 2.0, b.y() + b.h() * 2.0, 0);
            });
            ctx.waitTicks(2);
            ctx.takeScreenshot("cobalmoon-editor-moved");
            float scaleAfter = ctx.computeOnClient(mc -> fps.layout().scale);
            check(scaleAfter > scaleBefore * 1.4f, "corner handle resized FPS: " + scaleBefore + " -> " + scaleAfter);

            // Back to the settings view; open the colour wheel for the FPS text colour and pick a colour.
            ctx.runOnClient(mc -> {
                HudEditor ed = Platform.currentEditor();
                var b = ed.modeButton();
                ed.mouseClicked(b.x() + b.w() / 2.0, b.y() + b.h() / 2.0, 0, false);
                check(ed.panelsVisible(), "Back to settings shows the panels");
                ed.openPicker("Color de texto", () -> fps.layout().textColor, v -> fps.layout().textColor = v, 120);
            });
            ctx.waitTicks(2);
            int colourBefore = ctx.computeOnClient(mc -> fps.layout().textColor);
            ctx.runOnClient(mc -> {
                var p = Platform.currentEditor().picker();
                check(p != null, "colour picker open");
                // Click on the wheel: left edge = cyan-ish hue (180°), fully saturated.
                double cx = pickerX(p) + 8 + 42, cy = pickerY(p) + 20 + 42;
                Platform.currentEditor().mouseClicked(cx - 40, cy, 0, false);
                Platform.currentEditor().mouseReleased(cx - 40, cy, 0);
            });
            ctx.waitTicks(2);
            ctx.takeScreenshot("cobalmoon-color-picker");
            int colourAfter = ctx.computeOnClient(mc -> fps.layout().textColor);
            check(colourAfter != colourBefore && ((colourAfter >> 16) & 0xFF) < 0x40 && (colourAfter & 0xFF) > 0xC0,
                    "wheel picked a cyan colour: " + Integer.toHexString(colourAfter));
            ctx.runOnClient(mc -> Platform.currentEditor().keyPressed(256, false)); // Esc closes the picker only
            check(ctx.computeOnClient(mc -> Platform.currentEditor() != null && Platform.currentEditor().picker() == null),
                    "Esc closes the picker but not the editor");
            ctx.takeScreenshot("cobalmoon-editor-selected");

            // 5. Closing the editor saves to disk (the anchor may have changed again while resizing).
            String finalAnchor = ctx.computeOnClient(mc -> fps.layout().anchor.name());
            ctx.runOnClient(mc -> Platform.closeScreen());
            ctx.waitTicks(2);
            Path cfg = FabricLoader.getInstance().getConfigDir().resolve("cobalmoon/modules/fps.json");
            String saved = read(cfg);
            check(saved.contains("\"anchor\": \"" + finalAnchor + "\""), "fps.json has the new anchor:\n" + saved);

            // 6. Disabling a module is saved too and hides it.
            ctx.runOnClient(mc -> module("cps").setEnabled(false));
            ctx.runOnClient(mc -> CobalmoonClient.flushConfig());
            check(read(FabricLoader.getInstance().getConfigDir().resolve("cobalmoon/modules/cps.json")).contains("\"enabled\": false"),
                    "cps.json saved as disabled");
            check(!module("cps").hudElements().get(0).isVisible(), "disabled module hidden");
            ctx.waitTicks(2);
            ctx.takeScreenshot("cobalmoon-hud-after");

            pvpChecks(ctx, world);
        }
    }

    /** Phase 4: combat events, PvP HUD, zoom and auto messages in a real world. */
    private static void pvpChecks(ClientGameTestContext ctx, TestSingleplayerContext world) {
        ctx.runOnClient(mc -> {
            for (String id : List.of("cps", "ping", "combo", "reach", "crits", "armor", "potions", "cooldown", "hit_color",
                    "hitsounds", "particles", "toggle_sprint", "no_fog")) {
                module(id).setEnabled(true);
            }
            module("cooldown").setting("hide_ready").fromJson(false);
        });
        var hits = new java.util.concurrent.CopyOnWriteArrayList<dev.cobalmoon.api.event.Events.HitConfirmed>();
        var chat = new java.util.concurrent.CopyOnWriteArrayList<String>();
        ctx.runOnClient(mc -> {
            Cobalmoon.api().events().subscribe(dev.cobalmoon.api.event.Events.HitConfirmed.class, hits::add);
            Cobalmoon.api().events().subscribe(dev.cobalmoon.api.event.Events.ChatMessage.class, e -> chat.add(e.text()));
        });

        var server = world.getServer();
        server.runCommand("gamemode survival @a");
        server.runCommand("item replace entity @a armor.chest with minecraft:diamond_chestplate");
        server.runCommand("item replace entity @a armor.feet with minecraft:iron_boots");
        server.runCommand("item replace entity @a weapon.mainhand with minecraft:diamond_sword");
        server.runCommand("effect give @a minecraft:speed 90 1");
        // A target 2 blocks in front of the player, at eye level of the crosshair.
        server.runCommand("execute as @p at @s run summon minecraft:villager ^ ^ ^2 {NoAI:1b,Silent:1b,Invulnerable:0b}");
        ctx.waitTicks(10);

        var attacks = new java.util.concurrent.CopyOnWriteArrayList<dev.cobalmoon.api.event.Events.Attack>();
        ctx.runOnClient(mc -> Cobalmoon.api().events().subscribe(dev.cobalmoon.api.event.Events.Attack.class, attacks::add));
        ctx.takeScreenshot("cobalmoon-before-attack");
        for (int i = 0; i < 3 && attacks.isEmpty(); i++) {
            ctx.getInput().holdMouseFor(0, 3);
            ctx.waitTicks(15); // let the weapon recharge and the hurt animation start
        }
        int clickAttacks = attacks.size();
        String aim = ctx.computeOnClient(mc -> String.valueOf(mc.hitResult == null ? null : mc.hitResult.getType())
                + (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult ehr ? " " + ehr.getEntity().getType() : "")
                + " missTime=" + mc.gameMode.hasMissTime()
                + " mouseGrabbed=" + mc.mouseHandler.isMouseGrabbed()
                + " windowActive=" + mc.isWindowActive()
                + " cannotAttack=" + mc.player.cannotAttackWithItem(mc.player.getMainHandItem(), 0)
                + " strength=" + mc.player.getAttackStrengthScale(0f));
        if (attacks.isEmpty()) {
            // On 26.x the test framework's simulated clicks do not reach Minecraft#startAttack (verified
            // with Cobalmoon's mouse mixins disabled too). Real clicks are verified on 1.21.11; here the
            // game's own attack method is called so the rest of the pipeline is still exercised.
            ctx.runOnClient(mc -> {
                if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult ehr) mc.gameMode.attack(mc.player, ehr.getEntity());
            });
            ctx.waitTicks(10);
            aim += " | direct attack -> attacks=" + attacks.size() + " hits=" + hits.size();
        }
        dev.cobalmoon.core.CobalmoonClient.LOG.info("PVP-DIAG clickAttacks={} attacks={} hits={} aim={}", clickAttacks, attacks.size(), hits.size(), aim);
        check(!hits.isEmpty(), "attacking the villager produced a confirmed hit (attacks sent: " + attacks.size() + ", aiming at: " + aim + ")");
        double reach = hits.get(0).distance();
        check(reach > 0.5 && reach < 3.5, "reach measured to the hitbox: " + reach);
        ctx.takeScreenshot("cobalmoon-pvp-hud");

        // Zoom: hold C.
        ctx.getInput().holdKey(67);
        ctx.waitTicks(10);
        check(ctx.computeOnClient(mc -> dev.cobalmoon.core.modules.ZoomModule.isZooming()), "zoom active while C is held");
        ctx.takeScreenshot("cobalmoon-zoom");
        ctx.getInput().releaseKey(67);
        ctx.waitTicks(5);
        check(!ctx.computeOnClient(mc -> dev.cobalmoon.core.modules.ZoomModule.isZooming()), "zoom ends on release");

        // Auto messages: a server line containing a trigger makes the player send its message.
        ctx.runOnClient(mc -> {
            module("auto_messages").setting("delay").fromJson(0.0);
            module("auto_messages").setEnabled(true);
        });
        server.runCommand("say Winner: Player0");
        ctx.waitTicks(30);
        check(chat.stream().anyMatch(l -> l.contains("gg") && !l.contains("Winner")), "auto message sent; chat seen: " + chat);
        ctx.takeScreenshot("cobalmoon-auto-message");

        visualChecks(ctx, world);
    }

    /** Before/after screenshots of the visual modules (inspected by a person; the mixins are checked by loading). */
    private static void visualChecks(ClientGameTestContext ctx, TestSingleplayerContext world) {
        var server = world.getServer();
        ctx.runOnClient(mc -> {
            for (String id : List.of("armor", "potions", "combo", "reach", "crits", "ping", "coordinates", "cooldown", "cps", "keystrokes")) {
                module(id).setEnabled(false);
            }
            module("hit_color").setting("color").fromJson("#E01E64FF"); // strong blue, easy to see
        });
        // Hit colour: hurt the villager and capture while its hurt overlay is visible.
        ctx.runOnClient(mc -> {
            if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult ehr) mc.gameMode.attack(mc.player, ehr.getEntity());
        });
        ctx.waitTicks(3);
        ctx.takeScreenshot("cobalmoon-hit-color");
        server.runCommand("kill @e[type=minecraft:villager]");

        // Fire overlay: vanilla height, then lowered.
        // Resistance V instead of fire resistance: some versions hide the fire overlay when fire-resistant.
        server.runCommand("effect give @a minecraft:resistance 60 4 true");
        server.runCommand("effect give @a minecraft:regeneration 60 4 true");
        server.runCommand("execute at @p run setblock ~ ~ ~ minecraft:fire");
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> module("low_fire").setEnabled(false));
        ctx.waitTicks(2);
        ctx.takeScreenshot("cobalmoon-fire-vanilla");
        ctx.runOnClient(mc -> {
            module("low_fire").setting("height").fromJson(0.5);
            module("low_fire").setEnabled(true);
        });
        ctx.waitTicks(2);
        ctx.takeScreenshot("cobalmoon-fire-low");
        server.runCommand("execute at @p run fill ~-1 ~ ~-1 ~1 ~1 ~1 minecraft:air replace minecraft:fire");
        server.runCommand("effect clear @a");

        // Shield: vanilla position, then lowered.
        server.runCommand("item replace entity @a weapon.offhand with minecraft:shield");
        ctx.waitTicks(30);
        ctx.runOnClient(mc -> module("low_shield").setEnabled(false));
        ctx.waitTicks(2);
        ctx.takeScreenshot("cobalmoon-shield-vanilla");
        ctx.runOnClient(mc -> {
            module("low_shield").setting("height").fromJson(0.5);
            module("low_shield").setEnabled(true);
        });
        ctx.waitTicks(2);
        ctx.takeScreenshot("cobalmoon-shield-low");

        phase5Checks(ctx, world);
    }

    /** Phase 5: fight analysis + replay, shareable codes, server profiles, training. */
    private static void phase5Checks(ClientGameTestContext ctx, TestSingleplayerContext world) {
        var server = world.getServer();
        var fights = (dev.cobalmoon.core.modules.FightAnalysisModule) module("fight_analysis");

        // --- Fight analysis: a fight against a mob (enabled for the test), ended by the target dying.
        ctx.runOnClient(mc -> {
            fights.setting("mobs").fromJson(true);
            fights.setting("timeout").fromJson(3.0);
        });
        server.runCommand("execute as @p at @s run summon minecraft:villager ^ ^ ^2 {NoAI:1b,Silent:1b}");
        ctx.waitTicks(10);
        for (int i = 0; i < 3; i++) {
            ctx.runOnClient(mc -> {
                if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult ehr) mc.gameMode.attack(mc.player, ehr.getEntity());
            });
            ctx.waitTicks(12);
        }
        server.runCommand("kill @e[type=minecraft:villager]");
        ctx.waitTicks(80); // death animation or timeout ends the fight
        var latest = ctx.computeOnClient(mc -> fights.history().latest());
        check(latest != null && latest.hits() >= 1 && latest.track().size() > 2,
                "fight recorded with hits and a replay track: " + latest);
        ctx.takeScreenshot("cobalmoon-fight-summary");

        // Replay viewer inside the editor.
        ctx.getInput().pressKey(RIGHT_SHIFT);
        ctx.waitFor(mc -> Platform.currentEditor() != null);
        ctx.runOnClient(mc -> {
            HudEditor ed = Platform.currentEditor();
            ed.openReplay(fights.history().replays());
            ed.replay().seek(latest.durationMs() / 2000f);
        });
        ctx.waitTicks(3);
        ctx.takeScreenshot("cobalmoon-replay");
        ctx.runOnClient(mc -> Platform.currentEditor().keyPressed(256, false));
        check(ctx.computeOnClient(mc -> Platform.currentEditor() != null && Platform.currentEditor().replay() == null),
                "Esc closes the replay but not the editor");

        // --- Shareable code: copy, change something, paste back.
        HudElement fps = module("fps").hudElements().get(0);
        ctx.runOnClient(mc -> {
            HudEditor ed = Platform.currentEditor();
            float scale = fps.layout().scale;
            ed.copyCode(dev.cobalmoon.core.share.ConfigShare.Scope.ALL);
            String code = mc.keyboardHandler.getClipboard();
            check(code.startsWith("CBM1:"), "clipboard has a Cobalmoon code");
            fps.layout().setScale(0.5f);
            module("crosshair").setEnabled(false);
            ed.pasteCode();
            check(fps.layout().scale == scale, "pasted code restored the FPS scale: " + fps.layout().scale + " vs " + scale);
            check(module("crosshair").isEnabled(), "pasted code restored module state");
            check(ed.shareStatus().length() > 0, "status shown");
            ed.importCode("not a code");
            check(ed.shareStatus().length() > 0, "bad code reported, not crashed");
        });
        ctx.runOnClient(mc -> Platform.closeScreen());

        // --- Server profile: saved for example.net, applied on a subdomain, restored on leave.
        var profiles = (dev.cobalmoon.core.modules.ServerProfilesModule) module("server_profiles");
        ctx.runOnClient(mc -> {
            float normal = fps.layout().scale;
            fps.layout().setScale(2.5f);
            profiles.saveFor("example.net", dev.cobalmoon.core.share.ConfigShare.collect(
                    Cobalmoon.api().modules().all(), dev.cobalmoon.core.share.ConfigShare.Scope.ALL, dev.cobalmoon.core.share.ConfigShare.NOT_SHARED));
            fps.layout().setScale(normal);
            profiles.simulateJoin("mc.example.net:25565");
            check("example.net".equals(profiles.activeHost()), "profile of the parent domain loaded");
            check(fps.layout().scale == 2.5f, "profile applied: " + fps.layout().scale);
            profiles.simulateLeave();
            check(fps.layout().scale == normal, "your normal settings restored on leave: " + fps.layout().scale);
        });

        // --- Training: CPS (clicks counted) and strafe aim (practice target spawned, aim measured).
        var training = (dev.cobalmoon.core.modules.TrainingModule) module("training");
        ctx.runOnClient(mc -> {
            training.setEnabled(true);
            training.configure(dev.cobalmoon.core.modules.TrainingModule.Exercise.CPS, 5);
            training.start();
        });
        ctx.waitTicks(65); // 3 s countdown
        for (int i = 0; i < 12; i++) {
            ctx.getInput().pressMouse(0);
            ctx.waitTicks(2);
        }
        ctx.waitTicks(90);
        List<String> cpsResult = ctx.computeOnClient(mc -> training.lastResult());
        check(cpsResult.size() >= 3 && cpsResult.stream().anyMatch(l -> l.matches(".*\\D(2|3)\\.\\d.*")),
                "CPS result around 12 clicks / 5 s: " + cpsResult);
        ctx.takeScreenshot("cobalmoon-training-cps");

        ctx.runOnClient(mc -> {
            training.configure(dev.cobalmoon.core.modules.TrainingModule.Exercise.AIM, 5);
            training.start();
        });
        ctx.waitTicks(65 + 110);
        List<String> aimResult = ctx.computeOnClient(mc -> training.lastResult());
        check(aimResult.size() >= 3 && aimResult.get(1).matches(".*\\D(\\d\\d|100)%.*"),
                "aim result measured the practice target in front of the player: " + aimResult);
        ctx.takeScreenshot("cobalmoon-training-aim");
        ctx.waitTicks(10);
        check(ctx.computeOnClient(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                mc.player.getBoundingBox().inflate(10), e -> e.getType().getDescriptionId().equals("entity.minecraft.villager")).isEmpty()),
                "practice target removed afterwards");
    }

    /** Each jar passed through fabric.addMods must belong to a loaded mod. Returns "id version" strings. */
    private static List<String> checkCompatModsLoaded() {
        String prop = System.getProperty("fabric.addMods", "");
        List<Path> jars = java.util.Arrays.stream(prop.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(s -> !s.isBlank()).map(s -> Path.of(s).toAbsolutePath().normalize()).toList();
        check(!jars.isEmpty(), "no compatibility mods were passed");
        List<String> loaded = new java.util.ArrayList<>();
        for (Path jar : jars) {
            var match = FabricLoader.getInstance().getAllMods().stream()
                    .filter(m -> m.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH
                            && m.getOrigin().getPaths().stream().anyMatch(p -> p.toAbsolutePath().normalize().equals(jar)))
                    .findFirst();
            check(match.isPresent(), "compat mod not loaded: " + jar.getFileName());
            loaded.add(match.get().getMetadata().getId() + " " + match.get().getMetadata().getVersion().getFriendlyString());
        }
        return loaded;
    }

    private static int pickerX(dev.cobalmoon.core.ui.ColorPicker p) { return p.bounds()[0]; }

    private static int pickerY(dev.cobalmoon.core.ui.ColorPicker p) { return p.bounds()[1]; }

    private static Module module(String id) {
        return Cobalmoon.api().modules().get(id).orElseThrow(() -> new AssertionError("missing module " + id));
    }

    private static boolean enabled(ClientGameTestContext ctx, String id) {
        return ctx.computeOnClient(mc -> module(id).isEnabled());
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new AssertionError("cannot read " + p, e);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("Cobalmoon game test failed: " + what);
    }
}
