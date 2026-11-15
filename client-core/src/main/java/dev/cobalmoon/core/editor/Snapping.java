/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.editor;

import dev.cobalmoon.core.render.HudRenderer.Bounds;

import java.util.ArrayList;
import java.util.List;

/**
 * Snaps a dragged rectangle to the screen edges, the screen centre lines and the edges/centres of
 * other elements, independently on each axis.
 */
public final class Snapping {
    public static final int DEFAULT_THRESHOLD = 4;

    private Snapping() {}

    /** A guide line to draw: vertical (x = pos) or horizontal (y = pos). */
    public record Guide(boolean vertical, int pos) {}

    public record Result(int x, int y, List<Guide> guides) {}

    public static Result snap(int x, int y, int w, int h, int screenW, int screenH, List<Bounds> others, int threshold) {
        List<Integer> xs = new ArrayList<>(List.of(0, screenW / 2, screenW));
        List<Integer> ys = new ArrayList<>(List.of(0, screenH / 2, screenH));
        for (Bounds b : others) {
            xs.add(b.x()); xs.add(b.x() + b.w() / 2); xs.add(b.right());
            ys.add(b.y()); ys.add(b.y() + b.h() / 2); ys.add(b.bottom());
        }
        List<Guide> guides = new ArrayList<>();
        int[] sx = axis(x, w, xs, threshold);
        int[] sy = axis(y, h, ys, threshold);
        if (sx != null) guides.add(new Guide(true, sx[1]));
        if (sy != null) guides.add(new Guide(false, sy[1]));
        return new Result(sx != null ? sx[0] : x, sy != null ? sy[0] : y, guides);
    }

    /** Returns {snappedStart, line} for the closest match of start/centre/end against the lines, or null. */
    private static int[] axis(int start, int size, List<Integer> lines, int threshold) {
        int[] best = null;
        int bestDist = threshold + 1;
        int[] offsets = {0, size / 2, size};
        for (int line : lines) {
            for (int off : offsets) {
                int d = Math.abs(start + off - line);
                if (d < bestDist) {
                    bestDist = d;
                    best = new int[]{line - off, line};
                }
            }
        }
        return best;
    }
}
