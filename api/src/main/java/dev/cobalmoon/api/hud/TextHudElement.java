/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.hud;

import java.util.List;

/**
 * HUD element that shows one or more lines of text using the layout's colours. Lines may start
 * with a label separated by {@link #LABEL_SEPARATOR} (e.g. {@code "FPS\u0000144"}): the label is drawn
 * in the accent colour and the value in the text colour.
 */
public abstract class TextHudElement extends HudElement {
    public static final char LABEL_SEPARATOR = '\u0000';
    public static final int PADDING = 4;
    public static final int LINE_SPACING = 2;

    protected TextHudElement(String id, String name, HudLayout defaults) {
        super(id, name, defaults);
    }

    protected TextHudElement(String id, String name, Anchor anchor, int offsetX, int offsetY) {
        this(id, name, new HudLayout(anchor, offsetX, offsetY));
    }

    /** Lines to draw. {@code preview} is true in the HUD editor. */
    protected abstract List<String> lines(boolean preview);

    /** Helper for a "label value" line. */
    protected static String labeled(String label, String value) {
        return label + LABEL_SEPARATOR + value;
    }

    private static String plain(String line) {
        return line.replace(String.valueOf(LABEL_SEPARATOR), " ");
    }

    @Override
    public int width(HudCanvas c, boolean preview) {
        int w = 0;
        for (String l : lines(preview)) w = Math.max(w, c.textWidth(plain(l)));
        return w + PADDING * 2;
    }

    @Override
    public int height(HudCanvas c, boolean preview) {
        int n = Math.max(1, lines(preview).size());
        return n * c.fontHeight() + (n - 1) * LINE_SPACING + PADDING * 2;
    }

    @Override
    public void render(HudCanvas c, boolean preview) {
        HudLayout l = layout();
        int y = PADDING;
        for (String line : lines(preview)) {
            int sep = line.indexOf(LABEL_SEPARATOR);
            if (sep >= 0) {
                String label = line.substring(0, sep) + " ";
                c.text(label, PADDING, y, l.accentColor, l.shadow);
                c.text(line.substring(sep + 1), PADDING + c.textWidth(label), y, l.textColor, l.shadow);
            } else {
                c.text(line, PADDING, y, l.textColor, l.shadow);
            }
            y += c.fontHeight() + LINE_SPACING;
        }
    }
}
