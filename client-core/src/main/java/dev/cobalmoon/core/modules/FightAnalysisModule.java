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
import dev.cobalmoon.api.setting.BooleanSetting;
import dev.cobalmoon.api.setting.NumberSetting;
import dev.cobalmoon.api.theme.DefaultTokens;
import dev.cobalmoon.core.fight.FightHistory;
import dev.cobalmoon.core.fight.FightRecord;
import dev.cobalmoon.core.fight.FightStats;
import dev.cobalmoon.core.ui.Tr;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

/**
 * Post-fight analysis: records each duel (accuracy, combos, reach, damage, reaction time and both
 * players' movement) and, when it ends, shows a summary compared with your previous fights. Everything
 * stays on this computer ({@code config/cobalmoon/fights.json}).
 */
public final class FightAnalysisModule extends Module {
    private final NumberSetting timeout = setting(new NumberSetting("timeout", "cobalmoon.setting.fights.timeout", 6, 3, 30, 1));
    private final BooleanSetting mobs = setting(new BooleanSetting("mobs", "cobalmoon.setting.fights.mobs", false));
    private final NumberSetting summarySeconds = setting(new NumberSetting("summary_seconds", "cobalmoon.setting.fights.summary_seconds", 12, 0, 60, 1));

    /** Opens the replay viewer; set by the HUD editor. */
    private static volatile Consumer<List<FightRecord>> replayOpener;

    private final FightHistory history;
    private FightStats fight;
    private int opponentId = Integer.MIN_VALUE;
    private int tick;
    private float lastHealth = -1;
    private final java.util.Map<Integer, Float> healthAtAttack = new java.util.HashMap<>();
    private FightRecord summary;
    private long summaryUntil;

    public FightAnalysisModule(Path configDir) {
        super("fight_analysis", "cobalmoon.module.fight_analysis", "cobalmoon.module.fight_analysis.desc", Category.TRAINING);
        history = new FightHistory(configDir.resolve("fights.json"));
        setting(new ActionSetting("replay", "cobalmoon.setting.fights.replay", () -> {
            Consumer<List<FightRecord>> o = replayOpener;
            if (o != null) o.accept(history.replays());
        }));
        setting(new ActionSetting("clear", "cobalmoon.setting.fights.clear", history::clear));

        HudLayout l = new HudLayout(Anchor.TOP_CENTER, 0, 46);
        l.backgroundColor = (0xE6 << 24) | (DefaultTokens.NIGHT_RAISED & 0xFFFFFF);
        hud(new SummaryElement(l));

        on(Events.MouseButton.class, e -> {
            if (fight != null && e.pressed() && e.inGame() && e.button() == Events.MouseButton.LEFT) fight.swing(e.timeNanos());
        });
        on(Events.Attack.class, e -> {
            if (!e.targetIsPlayer() && !mobs.get()) return;
            Entity target = level().getEntity(e.entityId());
            ensureFight(e.entityId(), target);
            fight.attack(System.nanoTime());
            if (target instanceof LivingEntity le) healthAtAttack.put(e.entityId(), le.getHealth());
        });
        on(Events.HitConfirmed.class, e -> {
            if (fight == null || e.entityId() != opponentId) return;
            Entity target = level().getEntity(e.entityId());
            float before = healthAtAttack.getOrDefault(e.entityId(), -1f);
            float now = target instanceof LivingEntity le ? le.getHealth() : -1f;
            float dealt = before >= 0 && now >= 0 ? Math.max(0, before - now) : 0;
            fight.hit(System.nanoTime(), e.distance(), e.critical(), dealt, target != null ? target.getX() : 0, target != null ? target.getZ() : 0);
        });
        on(Events.PlayerHurt.class, e -> {
            Player self = Minecraft.getInstance().player;
            if (self == null) return;
            float dmg = lastHealth >= 0 ? Math.max(0, lastHealth - e.healthAfter()) : 0;
            if (fight == null) {
                Player near = nearestPlayer(self, 6);
                if (near == null) return;
                ensureFight(near.getId(), near);
            }
            fight.hurt(System.nanoTime(), dmg, self.getX(), self.getZ());
        });
        on(Events.ClientTick.class, e -> tick());
        on(Events.ServerLeave.class, e -> end(FightRecord.Outcome.UNKNOWN));
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    public static void setReplayOpener(Consumer<List<FightRecord>> opener) {
        replayOpener = opener;
    }

    public FightHistory history() { return history; }

    public boolean inFight() { return fight != null; }

    private static net.minecraft.client.multiplayer.ClientLevel level() {
        return Minecraft.getInstance().level;
    }

    private static Player nearestPlayer(Player self, double radius) {
        Player best = null;
        double bestD = radius * radius;
        for (Player p : self.level().players()) {
            if (p == self) continue;
            double d = p.distanceToSqr(self);
            if (d <= bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private void ensureFight(int id, Entity target) {
        if (fight != null && id == opponentId) return;
        if (fight != null) end(FightRecord.Outcome.UNKNOWN);
        opponentId = id;
        fight = new FightStats(System.nanoTime(), target != null ? target.getName().getString() : "?");
        healthAtAttack.clear();
    }

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        Player self = mc.player;
        if (self == null || mc.level == null) return;
        if (lastHealth < 0 || fight == null) lastHealth = self.getHealth();
        if (fight == null) return;
        Entity opp = mc.level.getEntity(opponentId);
        long now = System.nanoTime();
        if (++tick % 2 == 0) {
            fight.sample(now, self.getX(), self.getZ(), self.getYRot(),
                    opp != null ? opp.getX() : Double.NaN, opp != null ? opp.getZ() : Double.NaN);
        }
        lastHealth = self.getHealth();
        if (self.isDeadOrDying()) end(FightRecord.Outcome.LOSS);
        else if (opp instanceof LivingEntity le && le.isDeadOrDying()) end(FightRecord.Outcome.WIN);
        else if (now - fight.lastCombat() > timeout.get() * 1e9) end(FightRecord.Outcome.UNKNOWN);
    }

    private void end(FightRecord.Outcome outcome) {
        if (fight == null) return;
        FightStats f = fight;
        fight = null;
        opponentId = Integer.MIN_VALUE;
        if (f.hits() == 0 && outcome == FightRecord.Outcome.UNKNOWN) return; // you never landed a hit: not a fight
        FightRecord r = f.finish(System.nanoTime(), outcome, System.currentTimeMillis());
        history.add(r);
        summary = r;
        summaryUntil = System.nanoTime() + (long) (summarySeconds.get() * 1e9);
    }

    // ---- summary card -------------------------------------------------------------------------

    static String signed(double v, String fmt) {
        if (Double.isNaN(v) || Math.abs(v) < 1e-9) return "";
        return " (" + (v > 0 ? "+" : "") + String.format(Locale.ROOT, fmt, v) + ")";
    }

    List<String> summaryLines(FightRecord r) {
        List<String> out = new ArrayList<>();
        String outcome = Tr.of("cobalmoon.fights.outcome." + r.outcome().name().toLowerCase(Locale.ROOT));
        out.add(Tr.of("cobalmoon.fights.vs", r.opponent()) + TextHudElement.LABEL_SEPARATOR + outcome
                + String.format(Locale.ROOT, " · %.1fs", r.durationMs() / 1000.0));
        out.add(line("cobalmoon.fights.accuracy", Math.round(r.accuracy() * 100) + "%" + d(r, x -> x.accuracy() * 100, "%.0f")));
        out.add(line("cobalmoon.fights.combo", r.maxCombo() + d(r, FightRecord::maxCombo, "%.0f")));
        out.add(line("cobalmoon.fights.reach", String.format(Locale.ROOT, "%.2f / %.2f", r.avgReach(), r.maxReach())));
        out.add(line("cobalmoon.fights.damage", String.format(Locale.ROOT, "%.1f / %.1f", r.damageDealt() / 2, r.damageTaken() / 2) + " ♥"));
        out.add(line("cobalmoon.fights.crits", Integer.toString(r.crits())));
        if (r.avgReactionMs() >= 0) out.add(line("cobalmoon.fights.reaction", r.avgReactionMs() + " ms" + d(r, x -> x.avgReactionMs(), "%.0f")));
        return out;
    }

    private String d(FightRecord r, ToDoubleFunction<FightRecord> metric, String fmt) {
        return signed(history.delta(r, metric), fmt);
    }

    private static String line(String key, String value) {
        return Tr.of(key) + TextHudElement.LABEL_SEPARATOR + value;
    }

    private final class SummaryElement extends TextHudElement {
        SummaryElement(HudLayout l) {
            super("fight_summary", "cobalmoon.module.fight_analysis", l);
        }

        @Override
        protected boolean shouldRender() {
            return summary != null && System.nanoTime() < summaryUntil;
        }

        @Override
        protected List<String> lines(boolean preview) {
            FightRecord r = summary != null ? summary : history.latest();
            if (r == null) {
                return preview ? List.of(labeled(Tr.of("cobalmoon.fights.vs", "Alex"), Tr.of("cobalmoon.fights.outcome.win")),
                        labeled(Tr.of("cobalmoon.fights.accuracy"), "64% (+5)")) : List.of();
            }
            return summaryLines(r);
        }
    }
}
