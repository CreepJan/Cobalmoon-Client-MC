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
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            ctx.waitTicks(40);

            // 1. Defaults: five built-in modules, HUD modules on, coordinates and crosshair off.
            List<String> ids = ctx.computeOnClient(mc -> Cobalmoon.api().modules().all().stream().map(Module::id).toList());
            check(ids.equals(List.of("fps", "cps", "keystrokes", "coordinates", "crosshair")), "modules: " + ids);
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
            ctx.runOnClient(mc -> {
                HudEditor ed = Platform.currentEditor();
                check(ed.keyPressed(258, false) && !ed.panelsVisible(), "Tab hides the panels");
                int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
                double startX = fps.layout().x(sw, 10) + 4, startY = fps.layout().y(sh, 10) + 4;
                check(ed.mouseClicked(startX, startY, 0, false), "click on FPS element");
                check(ed.selection().contains(fps), "FPS element selected");
                ed.mouseDragged(sw / 2.0 + 30, sh / 2.0 + 25);
                ed.mouseReleased(sw / 2.0 + 30, sh / 2.0 + 25, 0);
            });
            ctx.waitTicks(2);
            ctx.takeScreenshot("cobalmoon-editor-moved");
            String after = ctx.computeOnClient(mc -> fps.layout().anchor.name());
            check(!before.equals(after), "anchor changed after drag: " + before + " -> " + after);

            // Panels back: the right panel now shows the FPS element's style and module settings.
            ctx.runOnClient(mc -> Platform.currentEditor().keyPressed(258, false));
            ctx.waitTicks(2);
            ctx.takeScreenshot("cobalmoon-editor-selected");

            // 5. Closing the editor saves to disk.
            ctx.runOnClient(mc -> Platform.closeScreen());
            ctx.waitTicks(2);
            Path cfg = FabricLoader.getInstance().getConfigDir().resolve("cobalmoon/modules/fps.json");
            String saved = read(cfg);
            check(saved.contains("\"anchor\": \"" + after + "\""), "fps.json has the new anchor:\n" + saved);

            // 6. Disabling a module is saved too and hides it.
            ctx.runOnClient(mc -> module("cps").setEnabled(false));
            ctx.runOnClient(mc -> CobalmoonClient.flushConfig());
            check(read(FabricLoader.getInstance().getConfigDir().resolve("cobalmoon/modules/cps.json")).contains("\"enabled\": false"),
                    "cps.json saved as disabled");
            check(!module("cps").hudElements().get(0).isVisible(), "disabled module hidden");
            ctx.waitTicks(2);
            ctx.takeScreenshot("cobalmoon-hud-after");
        }
    }

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
