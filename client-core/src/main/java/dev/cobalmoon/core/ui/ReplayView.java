/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.ui;

import dev.cobalmoon.api.theme.DefaultTokens;
import dev.cobalmoon.core.fight.FightRecord;
import dev.cobalmoon.core.render.GuiCanvas;

import java.util.List;
import java.util.Locale;

/**
 * Lightweight replay: a top-down map of a recorded fight (your path in turquoise, your opponent's in
 * red, hits as markers) with a timeline to play or scrub. Space: play/pause · ←/→: other fights · Esc: close.
 */
public final class ReplayView {
    private final List<FightRecord> fights;
    private int index;
    private float t;
    private boolean playing = true;
    private long lastNanos;
    private boolean scrubbing;

    // layout cached from the last frame (for input)
    private int barX1, barX2, barY, playX, playY, prevX, nextX, navY;

    public ReplayView(List<FightRecord> fights) {
        this.fights = fights;
        this.index = Math.max(0, fights.size() - 1);
    }

    public int index() { return index; }
    public float time() { return t; }
    public boolean playing() { return playing; }

    private FightRecord current() {
        return fights.isEmpty() ? null : fights.get(index);
    }

    private float duration(FightRecord r) {
        float last = r.track().isEmpty() ? 0 : r.track().get(r.track().size() - 1)[0];
        return Math.max(last, r.durationMs() / 1000f);
    }

    /** Linear position on a track at time {@code time}: {selfX, selfZ, yaw, oppX, oppZ}. */
    public static float[] at(List<float[]> track, float time) {
        if (track.isEmpty()) return new float[]{0, 0, 0, Float.NaN, Float.NaN};
        if (time <= track.get(0)[0]) return slice(track.get(0));
        for (int i = 1; i < track.size(); i++) {
            float[] b = track.get(i);
            if (b[0] >= time) {
                float[] a = track.get(i - 1);
                float f = b[0] == a[0] ? 1 : (time - a[0]) / (b[0] - a[0]);
                return new float[]{lerp(a[1], b[1], f), lerp(a[2], b[2], f), b[3], lerp(a[4], b[4], f), lerp(a[5], b[5], f)};
            }
        }
        return slice(track.get(track.size() - 1));
    }

    private static float[] slice(float[] s) {
        return new float[]{s[1], s[2], s[3], s[4], s[5]};
    }

    private static float lerp(float a, float b, float f) {
        if (Float.isNaN(a)) return b;
        if (Float.isNaN(b)) return a;
        return a + (b - a) * f;
    }

    // ---- drawing -------------------------------------------------------------------------------

    public void render(GuiCanvas c, int mx, int my, int sw, int sh) {
        int x = 12, y = 10, w = sw - 24, h = sh - 20;
        c.fill(x, y, x + w, y + h, Theme.panel());
        c.outline(x, y, w, h, Theme.accent());
        FightRecord r = current();
        if (r == null) {
            c.centeredText(Tr.of("cobalmoon.replay.empty"), sw / 2, sh / 2 - 4, Theme.muted(), false);
            c.centeredText(Tr.of("cobalmoon.replay.close"), sw / 2, sh / 2 + 8, Theme.muted(), false);
            return;
        }
        long now = System.nanoTime();
        float dur = duration(r);
        if (playing && lastNanos != 0) t = Math.min(dur, t + (now - lastNanos) / 1e9f);
        if (t >= dur) playing = false;
        lastNanos = now;

        // header: title, navigation
        String outcome = Tr.of("cobalmoon.fights.outcome." + r.outcome().name().toLowerCase(Locale.ROOT));
        c.text(Tr.of("cobalmoon.fights.vs", r.opponent()) + " · " + outcome, x + 8, y + 7, Theme.text(), false);
        String nav = (index + 1) + "/" + fights.size();
        nextX = x + w - 16;
        prevX = nextX - c.textWidth(nav) - 22;
        navY = y + 7;
        c.text("<", prevX, navY, index > 0 ? Theme.accent() : Theme.muted(), false);
        c.text(nav, prevX + 12, navY, Theme.muted(), false);
        c.text(">", nextX, navY, index < fights.size() - 1 ? Theme.accent() : Theme.muted(), false);

        // map
        int statsW = Math.max(110, w / 4);
        int mx1 = x + 8, my1 = y + 22, mx2 = x + w - statsW - 12, my2 = y + h - 30;
        c.fill(mx1, my1, mx2, my2, Theme.panelSunken());
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (float[] s : r.track()) {
            for (int k : new int[]{1, 4}) {
                if (Float.isNaN(s[k]) || Float.isNaN(s[k + 1])) continue;
                minX = Math.min(minX, s[k]); maxX = Math.max(maxX, s[k]);
                minZ = Math.min(minZ, s[k + 1]); maxZ = Math.max(maxZ, s[k + 1]);
            }
        }
        float pad = 2;
        minX -= pad; maxX += pad; minZ -= pad; maxZ += pad;
        float scale = Math.min((mx2 - mx1) / Math.max(1f, maxX - minX), (my2 - my1) / Math.max(1f, maxZ - minZ));
        float ox = (mx1 + mx2) / 2f - (minX + maxX) / 2f * scale, oz = (my1 + my2) / 2f - (minZ + maxZ) / 2f * scale;

        // grid (one line per block when zoomed in, every 5 otherwise)
        int step = scale >= 12 ? 1 : 5;
        int grid = Theme.withAlpha(Theme.muted(), 0x22);
        for (int gx = (int) Math.ceil(minX / step) * step; gx <= maxX; gx += step) {
            int px = Math.round(ox + gx * scale);
            c.fill(px, my1, px + 1, my2, grid);
        }
        for (int gz = (int) Math.ceil(minZ / step) * step; gz <= maxZ; gz += step) {
            int pz = Math.round(oz + gz * scale);
            c.fill(mx1, pz, mx2, pz + 1, grid);
        }

        int self = Theme.accent(), opp = Theme.danger();
        for (float[] s : r.track()) {
            boolean past = s[0] <= t;
            dot(c, ox + s[1] * scale, oz + s[2] * scale, 1, past ? self : Theme.withAlpha(self, 0x40));
            if (!Float.isNaN(s[4])) dot(c, ox + s[4] * scale, oz + s[5] * scale, 1, past ? opp : Theme.withAlpha(opp, 0x40));
        }
        for (float[] m : r.marks()) {
            if (m[0] > t) continue;
            int col = m[1] == 0 ? self : opp;
            float px = ox + m[2] * scale, pz = oz + m[3] * scale;
            c.fill(Math.round(px) - 3, Math.round(pz) - 3, Math.round(px) + 3, Math.round(pz) + 3, 0xFF000000);
            c.fill(Math.round(px) - 2, Math.round(pz) - 2, Math.round(px) + 2, Math.round(pz) + 2, col);
        }
        float[] p = at(r.track(), t);
        float sx = ox + p[0] * scale, sz = oz + p[1] * scale;
        double yaw = Math.toRadians(p[2]);
        for (int i = 2; i <= 10; i += 2) dot(c, sx + (float) (-Math.sin(yaw) * i), sz + (float) (Math.cos(yaw) * i), 0, Theme.text());
        dot(c, sx, sz, 3, self);
        if (!Float.isNaN(p[3])) dot(c, ox + p[3] * scale, oz + p[4] * scale, 3, opp);

        // stats column
        int stX = x + w - statsW - 4, stY = my1 + 2;
        String[][] stats = {
                {"cobalmoon.fights.accuracy", Math.round(r.accuracy() * 100) + "%"},
                {"cobalmoon.fights.combo", Integer.toString(r.maxCombo())},
                {"cobalmoon.fights.reach", String.format(Locale.ROOT, "%.2f / %.2f", r.avgReach(), r.maxReach())},
                {"cobalmoon.fights.damage", String.format(Locale.ROOT, "%.1f / %.1f ♥", r.damageDealt() / 2, r.damageTaken() / 2)},
                {"cobalmoon.fights.crits", Integer.toString(r.crits())},
                {"cobalmoon.fights.reaction", r.avgReactionMs() >= 0 ? r.avgReactionMs() + " ms" : "-"},
        };
        for (String[] s : stats) {
            c.text(Tr.of(s[0]), stX, stY, Theme.muted(), false);
            c.text(s[1], stX, stY + 10, Theme.text(), false);
            stY += 24;
        }
        legend(c, stX, stY + 4, self, Tr.of("cobalmoon.replay.you"));
        legend(c, stX, stY + 16, opp, r.opponent());

        // timeline
        playX = x + 8;
        playY = y + h - 22;
        c.fill(playX, playY - 3, playX + 14, playY + 11, Theme.accent());
        c.centeredText(playing ? "||" : ">", playX + 7, playY, Theme.onAccent(), false);
        String time = fmt(t) + " / " + fmt(dur);
        barX1 = playX + 22;
        barX2 = x + w - 12 - c.textWidth(time) - 8;
        barY = playY + 3;
        c.fill(barX1, barY, barX2, barY + 3, Theme.border());
        int knob = barX1 + Math.round((barX2 - barX1) * (dur == 0 ? 0 : t / dur));
        c.fill(barX1, barY, knob, barY + 3, Theme.accent());
        for (float[] m : r.marks()) {
            int mxp = barX1 + Math.round((barX2 - barX1) * (dur == 0 ? 0 : m[0] / dur));
            c.fill(mxp, barY - 2, mxp + 1, barY + 5, m[1] == 0 ? self : opp);
        }
        c.fill(knob - 2, barY - 3, knob + 2, barY + 6, Theme.text());
        c.text(time, barX2 + 8, playY, Theme.muted(), false);
    }

    private static void legend(GuiCanvas c, int x, int y, int color, String label) {
        c.fill(x, y + 1, x + 6, y + 7, color);
        c.text(label, x + 10, y, Theme.text(), false);
    }

    private static void dot(GuiCanvas c, float x, float y, int r, int color) {
        int ix = Math.round(x), iy = Math.round(y);
        c.fill(ix - r, iy - r, ix + r + 1, iy + r + 1, color);
    }

    static String fmt(float s) {
        int sec = (int) s;
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }

    // ---- input ---------------------------------------------------------------------------------

    public void click(double mx, double my) {
        FightRecord r = current();
        if (r == null) return;
        if (my >= navY - 2 && my <= navY + 10) {
            if (mx >= prevX - 2 && mx <= prevX + 8) switchTo(index - 1);
            else if (mx >= nextX - 2 && mx <= nextX + 8) switchTo(index + 1);
            return;
        }
        if (mx >= playX && mx <= playX + 14 && my >= playY - 3 && my <= playY + 11) {
            togglePlay();
            return;
        }
        if (mx >= barX1 - 3 && mx <= barX2 + 3 && my >= barY - 5 && my <= barY + 8) {
            scrubbing = true;
            drag(mx);
        }
    }

    public void drag(double mx) {
        FightRecord r = current();
        if (!scrubbing || r == null) return;
        float f = (float) Math.max(0, Math.min(1, (mx - barX1) / Math.max(1.0, barX2 - barX1)));
        t = f * duration(r);
        playing = false;
    }

    public void release() {
        scrubbing = false;
    }

    public void togglePlay() {
        FightRecord r = current();
        if (r == null) return;
        if (!playing && t >= duration(r)) t = 0;
        playing = !playing;
        lastNanos = 0;
    }

    public void switchTo(int i) {
        if (i < 0 || i >= fights.size()) return;
        index = i;
        t = 0;
        playing = true;
        lastNanos = 0;
    }

    /** Space / arrows. Returns true if handled. */
    public boolean key(int key) {
        switch (key) {
            case 32 -> togglePlay();
            case 263 -> switchTo(index - 1);
            case 262 -> switchTo(index + 1);
            default -> { return false; }
        }
        return true;
    }

    /** Test hook: jump to a time. */
    public void seek(float seconds) {
        t = seconds;
        playing = false;
    }
}
