/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.modules;

import dev.cobalmoon.api.event.Events;
import dev.cobalmoon.api.hud.Anchor;
import dev.cobalmoon.api.hud.HudLayout;
import dev.cobalmoon.api.hud.TextHudElement;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.ActionSetting;
import dev.cobalmoon.api.setting.EnumSetting;
import dev.cobalmoon.api.setting.NumberSetting;
import dev.cobalmoon.api.theme.DefaultTokens;
import dev.cobalmoon.core.training.TrainingMetrics;
import dev.cobalmoon.core.ui.Tr;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Practice exercises in a local world. They only MEASURE what you do (clicks, aim, sprint resets,
 * bridging); nothing is automated. Starting one in a local world spawns a protected practice target
 * or gives you wool, using the game's own commands.
 */
public final class TrainingModule extends Module {
    public enum Exercise { CPS, AIM, WTAP, BRIDGE }

    private enum Phase { IDLE, COUNTDOWN, RUNNING, RESULT }

    static final String DUMMY_TAG = "cobalmoon_dummy";

    private final EnumSetting<Exercise> exercise = setting(new EnumSetting<>("exercise", "cobalmoon.setting.training.exercise", Exercise.CPS,
            e -> "cobalmoon.training." + e.name().toLowerCase(Locale.ROOT)));
    private final NumberSetting seconds = setting(new NumberSetting("seconds", "cobalmoon.setting.training.seconds", 15, 5, 60, 5));

    private Phase phase = Phase.IDLE;
    private Exercise running;
    private long phaseStart;
    private TrainingMetrics.Cps cps;
    private TrainingMetrics.Aim aim;
    private TrainingMetrics.WTap wtap;
    private TrainingMetrics.Bridge bridge;
    private List<String> result = List.of();
    private String message = "";

    public TrainingModule() {
        super("training", "cobalmoon.module.training", "cobalmoon.module.training.desc", Category.TRAINING);
        setting(new ActionSetting("start", "cobalmoon.setting.training.start", this::start));
        setting(new ActionSetting("stop", "cobalmoon.setting.training.stop", () -> finish(true)));

        HudLayout l = new HudLayout(Anchor.TOP_CENTER, 0, 70);
        l.backgroundColor = (0xE6 << 24) | (DefaultTokens.NIGHT_RAISED & 0xFFFFFF);
        hud(new TextHudElement("training", "cobalmoon.module.training", l) {
            @Override protected boolean shouldRender() { return phase != Phase.IDLE || !message.isEmpty(); }

            @Override
            protected List<String> lines(boolean preview) {
                if (phase == Phase.IDLE && message.isEmpty() && preview) return List.of(labeled(Tr.of("cobalmoon.training.cps"), "8.4 CPS"));
                return hudLines();
            }
        });

        on(Events.MouseButton.class, e -> {
            if (phase == Phase.RUNNING && running == Exercise.CPS && e.pressed() && e.inGame() && e.button() == Events.MouseButton.LEFT) {
                cps.click(e.timeNanos());
            }
        });
        on(Events.Attack.class, e -> {
            Player p = Minecraft.getInstance().player;
            if (phase == Phase.RUNNING && running == Exercise.WTAP && p != null) wtap.hit(p.isSprinting());
        });
        on(Events.ClientTick.class, e -> tick());
        on(Events.ServerLeave.class, e -> {
            phase = Phase.IDLE;
            message = "";
        });
    }

    public boolean isRunning() { return phase == Phase.COUNTDOWN || phase == Phase.RUNNING; }

    public List<String> lastResult() { return result; }

    public String message() { return message; }

    // ---- control ------------------------------------------------------------------------------

    public void start() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        Player p = mc.player;
        if (!mc.hasSingleplayerServer() || server == null || p == null) {
            message = Tr.of("cobalmoon.training.local_only");
            phase = Phase.IDLE;
            return;
        }
        cleanup();
        running = exercise.get();
        String name = p.getName().getString();
        switch (running) {
            case AIM, WTAP -> {
                run(server, "execute as " + name + " at @s run summon minecraft:villager ^ ^ ^3 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:[\"" + DUMMY_TAG + "\"]}");
                run(server, "effect give @e[tag=" + DUMMY_TAG + "] minecraft:resistance infinite 4 true");
                run(server, "effect give @e[tag=" + DUMMY_TAG + "] minecraft:regeneration infinite 4 true");
            }
            case BRIDGE -> run(server, "give " + name + " minecraft:white_wool 128");
            case CPS -> { }
        }
        message = "";
        result = List.of();
        phase = Phase.COUNTDOWN;
        phaseStart = System.nanoTime();
    }

    private static void run(IntegratedServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    private void cleanup() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) run(server, "kill @e[tag=" + DUMMY_TAG + "]");
    }

    private double elapsed() {
        return (System.nanoTime() - phaseStart) / 1e9;
    }

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null) return;
        if (phase == Phase.COUNTDOWN && elapsed() >= 3) {
            phase = Phase.RUNNING;
            phaseStart = System.nanoTime();
            cps = new TrainingMetrics.Cps();
            aim = new TrainingMetrics.Aim();
            wtap = new TrainingMetrics.WTap();
            bridge = new TrainingMetrics.Bridge(p.getX(), p.getY(), p.getZ());
        }
        if (phase != Phase.RUNNING) {
            if (phase == Phase.RESULT && elapsed() > 20) phase = Phase.IDLE;
            return;
        }
        boolean strafing = mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
        aim.tick(mc.hitResult instanceof EntityHitResult, strafing);
        wtap.tick(p.isSprinting());
        bridge.tick(blockCount(p.getInventory()), p.getX(), p.getY(), p.getZ());
        if (elapsed() >= seconds.get()) finish(false);
    }

    private static int blockCount(Inventory inv) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof BlockItem) n += s.getCount();
        }
        return n;
    }

    private void finish(boolean stoppedEarly) {
        if (phase != Phase.RUNNING && phase != Phase.COUNTDOWN) return;
        double secs = phase == Phase.RUNNING ? Math.min(elapsed(), seconds.get()) : 0;
        if (phase == Phase.RUNNING) result = results(running, secs);
        if (stoppedEarly) result = prepend(result, Tr.of("cobalmoon.training.stopped"));
        cleanup();
        phase = Phase.RESULT;
        phaseStart = System.nanoTime();
    }

    private static List<String> prepend(List<String> list, String first) {
        List<String> out = new ArrayList<>();
        out.add(first);
        out.addAll(list);
        return out;
    }

    // ---- text ---------------------------------------------------------------------------------

    private static String l(String key, String value) {
        return Tr.of(key) + TextHudElement.LABEL_SEPARATOR + value;
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "—" : Math.round(v * 100) + "%";
    }

    List<String> results(Exercise ex, double secs) {
        List<String> out = new ArrayList<>();
        out.add(l("cobalmoon.training.result", Tr.of("cobalmoon.training." + ex.name().toLowerCase(Locale.ROOT))));
        switch (ex) {
            case CPS -> {
                out.add(l("cobalmoon.training.avg_cps", String.format(Locale.ROOT, "%.1f", cps.average(secs))));
                out.add(l("cobalmoon.training.best_cps", Integer.toString(cps.best())));
                out.add(l("cobalmoon.training.consistency", String.format(Locale.ROOT, "±%.1f", cps.consistency(phaseStart, secs))));
            }
            case AIM -> {
                out.add(l("cobalmoon.training.on_target", pct(aim.overall())));
                out.add(l("cobalmoon.training.on_target_strafing", pct(aim.whileStrafing())));
                out.add(l("cobalmoon.training.strafing_time", pct(aim.strafeShare())));
            }
            case WTAP -> {
                out.add(l("cobalmoon.training.hits", Integer.toString(wtap.hits())));
                out.add(l("cobalmoon.training.wtap_ratio", pct(wtap.ratio())));
            }
            case BRIDGE -> {
                out.add(l("cobalmoon.training.blocks", Integer.toString(bridge.placed())));
                out.add(l("cobalmoon.training.blocks_per_s", String.format(Locale.ROOT, "%.1f", bridge.blocksPerSecond(secs))));
                out.add(l("cobalmoon.training.distance", String.format(Locale.ROOT, "%.1f", bridge.distance())));
                out.add(l("cobalmoon.training.falls", Integer.toString(bridge.falls())));
            }
        }
        return out;
    }

    private List<String> hudLines() {
        if (!message.isEmpty() && phase == Phase.IDLE) return List.of(message);
        String name = running == null ? "" : Tr.of("cobalmoon.training." + running.name().toLowerCase(Locale.ROOT));
        return switch (phase) {
            case COUNTDOWN -> List.of(l("cobalmoon.module.training", name), Tr.of("cobalmoon.training.starts_in", (int) Math.ceil(3 - elapsed())));
            case RUNNING -> {
                double left = Math.max(0, seconds.get() - elapsed());
                List<String> live = new ArrayList<>();
                live.add(l("cobalmoon.module.training", name + String.format(Locale.ROOT, " · %.0fs", left)));
                live.add(liveMetric());
                yield live;
            }
            case RESULT -> result;
            case IDLE -> List.of();
        };
    }

    private String liveMetric() {
        double secs = Math.max(0.001, elapsed());
        return switch (running) {
            case CPS -> l("cobalmoon.training.avg_cps", String.format(Locale.ROOT, "%.1f", cps.average(secs)));
            case AIM -> l("cobalmoon.training.on_target_strafing", pct(aim.whileStrafing()));
            case WTAP -> l("cobalmoon.training.wtap_ratio", pct(wtap.ratio()) + " (" + wtap.hits() + ")");
            case BRIDGE -> l("cobalmoon.training.blocks", bridge.placed() + " · " + String.format(Locale.ROOT, "%.1f", bridge.distance()) + " m");
        };
    }

    /** Test hooks: choose the exercise and length without the menu. */
    public void configure(Exercise ex, int secs) {
        exercise.set(ex);
        seconds.set((double) secs);
    }
}
