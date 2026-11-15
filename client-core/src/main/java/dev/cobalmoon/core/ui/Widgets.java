/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.ui;

import dev.cobalmoon.api.setting.BooleanSetting;
import dev.cobalmoon.api.setting.ColorSetting;
import dev.cobalmoon.api.setting.EnumSetting;
import dev.cobalmoon.api.setting.NumberSetting;
import dev.cobalmoon.api.setting.Setting;
import dev.cobalmoon.core.render.GuiCanvas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Small immediate-style widget toolkit for Cobalmoon screens, drawn with {@link Theme} colours. */
public final class Widgets {
    private Widgets() {}

    public static final int ROW_H = 18;

    /** A full-width row inside a {@link Panel}. */
    public abstract static class Row {
        protected int height() { return ROW_H; }

        abstract void render(GuiCanvas c, int x, int y, int w, int mx, int my, boolean hovered);

        /** Mouse press at (mx, my) inside the row. Return true if handled. */
        boolean click(int x, int y, int w, double mx, double my, int button) { return false; }

        void drag(int x, int w, double mx) {}

        void release() {}

        boolean scroll(double amount) { return false; }

        /** Width this row needs to show its text without truncation. */
        int preferredWidth(GuiCanvas c) { return 0; }

        /** Called with the final row width before rendering (for rows whose height depends on it). */
        void layout(GuiCanvas c, int w) {}
    }

    public static final class Header extends Row {
        private final Supplier<String> text;

        public Header(Supplier<String> text) { this.text = text; }

        @Override protected int height() { return 16; }

        @Override int preferredWidth(GuiCanvas c) { return c.textWidth(text.get().toUpperCase(Locale.ROOT)) + 14; }

        @Override
        void render(GuiCanvas c, int x, int y, int w, int mx, int my, boolean hovered) {
            c.text(ellipsize(c, text.get().toUpperCase(Locale.ROOT), w - 12), x + 6, y + 6, Theme.muted(), false);
        }
    }

    /** Muted help text; wraps onto as many lines as needed instead of being cut. */
    public static final class Label extends Row {
        private final Supplier<String> text;
        private List<String> lines = List.of();

        public Label(Supplier<String> text) { this.text = text; }

        @Override
        void layout(GuiCanvas c, int w) {
            lines = wrap(c, text.get(), w - 12);
        }

        @Override protected int height() { return Math.max(1, lines.size()) * 10 + 8; }

        @Override
        void render(GuiCanvas c, int x, int y, int w, int mx, int my, boolean hovered) {
            int ly = y + 5;
            for (String l : lines) {
                c.text(l, x + 6, ly, Theme.muted(), false);
                ly += 10;
            }
        }
    }

    /** Greedy word wrap; words longer than the width are split. */
    static List<String> wrap(GuiCanvas c, String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (c.textWidth(candidate) <= max) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) out.add(line.toString());
            line.setLength(0);
            while (c.textWidth(word) > max && word.length() > 1) {
                int end = word.length();
                while (end > 1 && c.textWidth(word.substring(0, end)) > max) end--;
                out.add(word.substring(0, end));
                word = word.substring(end);
            }
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    public static final class Toggle extends Row {
        private final Supplier<String> label;
        private final BooleanSupplier get;
        private final Consumer<Boolean> set;

        public Toggle(Supplier<String> label, BooleanSupplier get, Consumer<Boolean> set) {
            this.label = label;
            this.get = get;
            this.set = set;
        }

        public static Toggle of(BooleanSetting s) {
            return new Toggle(() -> Tr.of(s.name()), s::get, s::set);
        }

        private List<String> lines = List.of();

        @Override int preferredWidth(GuiCanvas c) { return c.textWidth(label.get()) + 46; }

        @Override void layout(GuiCanvas c, int w) { lines = wrap(c, label.get(), w - 40); }

        @Override protected int height() { return wrappedHeight(lines); }

        @Override
        void render(GuiCanvas c, int x, int y, int w, int mx, int my, boolean hovered) {
            int h = height();
            if (hovered) c.fill(x, y, x + w, y + h, Theme.hover());
            drawLines(c, lines, x + 6, y, h, Theme.text());
            drawSwitch(c, x + w - 28, y + (h - 10) / 2, get.getAsBoolean());
        }

        @Override
        boolean click(int x, int y, int w, double mx, double my, int button) {
            set.accept(!get.getAsBoolean());
            return true;
        }
    }

    public static final class Slider extends Row {
        private final Supplier<String> label;
        private final DoubleSupplier fraction;
        private final DoubleConsumer setFraction;
        private final Supplier<String> value;
        private boolean dragging;

        public Slider(Supplier<String> label, DoubleSupplier fraction, DoubleConsumer setFraction, Supplier<String> value) {
            this.label = label;
            this.fraction = fraction;
            this.setFraction = setFraction;
            this.value = value;
        }

        public static Slider of(NumberSetting s) {
            boolean integral = s.step() >= 1 && s.min() == Math.rint(s.min());
            return new Slider(() -> Tr.of(s.name()), s::fraction, s::setFraction,
                    () -> integral ? Integer.toString(s.intValue()) : String.format(Locale.ROOT, "%.2f", s.get()));
        }

        @Override protected int height() { return 26; }

        @Override int preferredWidth(GuiCanvas c) { return c.textWidth(label.get()) + c.textWidth(value.get()) + 22; }

        @Override
        void render(GuiCanvas c, int x, int y, int w, int mx, int my, boolean hovered) {
            if (hovered || dragging) c.fill(x, y, x + w, y + height(), Theme.hover());
            String v = value.get();
            c.text(ellipsize(c, label.get(), w - 16 - c.textWidth(v)), x + 6, y + 4, Theme.text(), false);
            c.text(v, x + w - 6 - c.textWidth(v), y + 4, Theme.accent(), false);
            int tx1 = x + 6, tx2 = x + w - 6, ty = y + 18;
            c.fill(tx1, ty, tx2, ty + 2, Theme.border());
            int kx = tx1 + (int) Math.round(fraction.getAsDouble() * (tx2 - tx1));
            c.fill(tx1, ty, kx, ty + 2, Theme.accent());
            c.fill(kx - 2, ty - 3, kx + 2, ty + 5, dragging ? Theme.accentStrong() : Theme.text());
        }

        @Override
        boolean click(int x, int y, int w, double mx, double my, int button) {
            dragging = true;
            drag(x, w, mx);
            return true;
        }

        @Override
        void drag(int x, int w, double mx) {
            if (dragging) setFraction.accept((mx - (x + 6)) / Math.max(1, w - 12));
        }

        @Override void release() { dragging = false; }
    }

    /** Cycles through values: left click forward, right click back. */
    public static final class Cycle extends Row {
        private final Supplier<String> label;
        private final Supplier<String> value;
        private final IntConsumer step;

        public Cycle(Supplier<String> label, Supplier<String> value, IntConsumer step) {
            this.label = label;
            this.value = value;
            this.step = step;
        }

        public static <E extends Enum<E>> Cycle of(EnumSetting<E> s) {
            return new Cycle(() -> Tr.of(s.name()), () -> Tr.of(s.label(s.get())), s::cycle);
        }

        @Override int preferredWidth(GuiCanvas c) { return c.textWidth(label.get()) + c.textWidth("‹ " + value.get() + " ›") + 22; }

        @Override
        void render(GuiCanvas c, int x, int y, int w, int mx, int my, boolean hovered) {
            if (hovered) c.fill(x, y, x + w, y + ROW_H, Theme.hover());
            String v = "‹ " + value.get() + " ›";
            c.text(ellipsize(c, label.get(), w - 16 - c.textWidth(v)), x + 6, y + 5, Theme.text(), false);
            c.text(v, x + w - 6 - c.textWidth(v), y + 5, Theme.accent(), false);
        }

        @Override
        boolean click(int x, int y, int w, double mx, double my, int button) {
            step.accept(button == 1 ? -1 : 1);
            return true;
        }
    }

    /** Colour from the preset swatches: click/right-click cycles, mouse wheel changes alpha. */
    public static final class ColorPick extends Row {
        private final Supplier<String> label;
        private final IntSupplier get;
        private final IntConsumer set;

        public ColorPick(Supplier<String> label, IntSupplier get, IntConsumer set) {
            this.label = label;
            this.get = get;
            this.set = set;
        }

        public static ColorPick of(ColorSetting s) {
            return new ColorPick(() -> Tr.of(s.name()), s::get, s::set);
        }

        private List<String> lines = List.of();

        @Override int preferredWidth(GuiCanvas c) { return c.textWidth(label.get()) + 34; }

        @Override void layout(GuiCanvas c, int w) { lines = wrap(c, label.get(), w - 30); }

        @Override protected int height() { return wrappedHeight(lines); }

        @Override
        void render(GuiCanvas c, int x, int y, int w, int mx, int my, boolean hovered) {
            int h = height();
            if (hovered) c.fill(x, y, x + w, y + h, Theme.hover());
            int argb = get.getAsInt();
            String hex = ColorSetting.toHex(argb);
            int sx = x + w - 18;
            if (hovered) {
                // the hex code replaces the label while hovering
                drawLines(c, List.of(hex), x + 6, y, h, Theme.muted());
            } else {
                drawLines(c, lines, x + 6, y, h, Theme.text());
            }
            y += (h - ROW_H) / 2; // centre the swatch vertically
            // checkerboard under the swatch so transparency is visible
            c.fill(sx, y + 3, sx + 12, y + 15, 0xFF808080);
            c.fill(sx, y + 3, sx + 6, y + 9, 0xFFC0C0C0);
            c.fill(sx + 6, y + 9, sx + 12, y + 15, 0xFFC0C0C0);
            c.fill(sx, y + 3, sx + 12, y + 15, argb);
            c.outline(sx - 1, y + 2, 14, 14, Theme.border());
        }

        @Override
        boolean click(int x, int y, int w, double mx, double my, int button) {
            int argb = get.getAsInt();
            int rgb = argb & 0xFFFFFF;
            int idx = -1;
            for (int i = 0; i < Theme.SWATCHES.length; i++) if ((Theme.SWATCHES[i] & 0xFFFFFF) == rgb) idx = i;
            int next = Math.floorMod(idx + (button == 1 ? -1 : 1), Theme.SWATCHES.length);
            set.accept((argb & 0xFF000000) | (Theme.SWATCHES[next] & 0xFFFFFF));
            return true;
        }

        @Override
        boolean scroll(double amount) {
            int argb = get.getAsInt();
            int a = Math.max(0, Math.min(255, (argb >>> 24) + (amount > 0 ? 17 : -17)));
            set.accept((a << 24) | (argb & 0xFFFFFF));
            return true;
        }
    }

    public static final class Button extends Row {
        private final Supplier<String> label;
        private final Runnable action;
        private final boolean primary;

        public Button(Supplier<String> label, Runnable action, boolean primary) {
            this.label = label;
            this.action = action;
            this.primary = primary;
        }

        @Override protected int height() { return 22; }

        @Override int preferredWidth(GuiCanvas c) { return c.textWidth(label.get()) + 28; }

        @Override
        void render(GuiCanvas c, int x, int y, int w, int mx, int my, boolean hovered) {
            int bx1 = x + 6, bx2 = x + w - 6, by1 = y + 3, by2 = y + 19;
            if (primary) {
                c.fill(bx1, by1, bx2, by2, hovered ? Theme.accentStrong() : Theme.accent());
                c.centeredText(label.get(), (bx1 + bx2) / 2, by1 + 4, Theme.onAccent(), false);
            } else {
                c.fill(bx1, by1, bx2, by2, hovered ? Theme.hover() : Theme.panelSunken());
                c.outline(bx1, by1, bx2 - bx1, by2 - by1, Theme.border());
                c.centeredText(label.get(), (bx1 + bx2) / 2, by1 + 4, Theme.text(), false);
            }
        }

        @Override
        boolean click(int x, int y, int w, double mx, double my, int button) {
            if (button != 0) return false;
            action.run();
            return true;
        }
    }

    /** Row for a module: name selects it, the switch toggles it. */
    public static final class ModuleRow extends Row {
        private final Supplier<String> name;
        private final BooleanSupplier enabled;
        private final Runnable toggle;
        private final Runnable select;
        private final BooleanSupplier selected;

        public ModuleRow(Supplier<String> name, BooleanSupplier enabled, Runnable toggle, Runnable select, BooleanSupplier selected) {
            this.name = name;
            this.enabled = enabled;
            this.toggle = toggle;
            this.select = select;
            this.selected = selected;
        }

        @Override int preferredWidth(GuiCanvas c) { return c.textWidth(name.get()) + 50; }

        @Override
        void render(GuiCanvas c, int x, int y, int w, int mx, int my, boolean hovered) {
            boolean sel = selected.getAsBoolean();
            if (sel) c.fill(x, y, x + w, y + ROW_H, Theme.hover());
            else if (hovered) c.fill(x, y, x + w, y + ROW_H, Theme.withAlpha(Theme.hover(), 0x99));
            if (sel) c.fill(x, y, x + 2, y + ROW_H, Theme.accent());
            c.text(ellipsize(c, name.get(), w - 44), x + 8, y + 5, enabled.getAsBoolean() ? Theme.text() : Theme.muted(), false);
            drawSwitch(c, x + w - 28, y + 4, enabled.getAsBoolean());
        }

        @Override
        boolean click(int x, int y, int w, double mx, double my, int button) {
            if (mx >= x + w - 32) toggle.run();
            else select.run();
            return true;
        }
    }

    /** Row height for wrapped label lines: one line keeps the standard row height. */
    static int wrappedHeight(List<String> lines) {
        return lines.size() <= 1 ? ROW_H : lines.size() * 10 + 8;
    }

    /** Draws label lines vertically centred in a row of height h. */
    static void drawLines(GuiCanvas c, List<String> lines, int x, int y, int h, int color) {
        int ly = y + (h - (lines.size() * 10 - 2)) / 2;
        for (String l : lines) {
            c.text(l, x, ly, color, false);
            ly += 10;
        }
    }

    static void drawSwitch(GuiCanvas c, int x, int y, boolean on) {
        c.fill(x, y, x + 22, y + 10, on ? Theme.accent() : Theme.withAlpha(Theme.muted(), 0x80));
        int kx = on ? x + 13 : x + 1;
        c.fill(kx, y + 1, kx + 8, y + 9, on ? Theme.onAccent() : Theme.text());
    }

    static String ellipsize(GuiCanvas c, String s, int max) {
        if (c.textWidth(s) <= max) return s;
        String dots = "…";
        int end = s.length();
        while (end > 0 && c.textWidth(s.substring(0, end) + dots) > max) end--;
        return s.substring(0, end) + dots;
    }

    /** Builds the right row for a setting type; unknown types become a read-only label. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Row forSetting(Setting<?> s) {
        if (s instanceof BooleanSetting b) return Toggle.of(b);
        if (s instanceof NumberSetting n) return Slider.of(n);
        if (s instanceof ColorSetting col) return ColorPick.of(col);
        if (s instanceof EnumSetting e) return Cycle.of(e);
        return new Label(() -> Tr.of(s.name()) + ": " + s.toJson());
    }

    /** Vertical, scrollable list of rows with a background. */
    public static final class Panel {
        public int x, y, w, h;
        private final List<Row> rows = new ArrayList<>();
        private double scroll;
        private Row active;
        private final Supplier<String> title;

        public Panel(Supplier<String> title) { this.title = title; }

        public void setRows(List<Row> newRows) {
            rows.clear();
            rows.addAll(newRows);
            active = null;
            clampScroll();
        }

        public void bounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            clampScroll();
        }

        private int contentTop() { return y + 22; }

        private int contentHeight() {
            int total = 0;
            for (Row r : rows) total += r.height();
            return total;
        }

        private void clampScroll() {
            int max = Math.max(0, contentHeight() - (y + h - contentTop()));
            scroll = Math.max(0, Math.min(max, scroll));
        }

        /** Width that fits the title and every row's text, clamped to [minW, maxW]. */
        public int preferredWidth(GuiCanvas c, int minW, int maxW) {
            int want = c.textWidth(title.get()) + 16;
            for (Row r : rows) want = Math.max(want, r.preferredWidth(c) + 4);
            return Math.max(minW, Math.min(maxW, want));
        }

        /** Lets rows whose height depends on the width (wrapped text) measure themselves. */
        public void layoutRows(GuiCanvas c) {
            for (Row r : rows) r.layout(c, w - 2);
            clampScroll();
        }

        public boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        public void render(GuiCanvas c, int mx, int my) {
            c.fill(x, y, x + w, y + h, Theme.panel());
            c.outline(x, y, w, h, Theme.border());
            c.text(title.get(), x + 8, y + 7, Theme.text(), false);
            c.fill(x + 1, y + 20, x + w - 1, y + 21, Theme.border());
            c.clip(x + 1, contentTop(), x + w - 1, y + h - 1);
            int ry = contentTop() - (int) scroll;
            for (Row r : rows) {
                int rh = r.height();
                if (ry + rh >= contentTop() && ry <= y + h) {
                    boolean hov = mx >= x && mx < x + w && my >= Math.max(ry, contentTop()) && my < ry + rh && my < y + h;
                    r.render(c, x + 1, ry, w - 2, mx, my, hov);
                }
                ry += rh;
            }
            c.endClip();
            int content = contentHeight(), view = y + h - contentTop();
            if (content > view) {
                int barH = Math.max(12, view * view / content);
                int barY = contentTop() + (int) ((view - barH) * (scroll / (content - view)));
                c.fill(x + w - 3, barY, x + w - 1, barY + barH, Theme.border());
            }
        }

        public boolean click(double mx, double my, int button) {
            if (!contains(mx, my) || my < contentTop()) return contains(mx, my);
            int ry = contentTop() - (int) scroll;
            for (Row r : rows) {
                int rh = r.height();
                if (my >= ry && my < ry + rh) {
                    active = r;
                    r.click(x + 1, ry, w - 2, mx, my, button);
                    return true;
                }
                ry += rh;
            }
            return true;
        }

        public void drag(double mx) {
            if (active != null) active.drag(x + 1, w - 2, mx);
        }

        public void release() {
            if (active != null) active.release();
            active = null;
        }

        public boolean scroll(double mx, double my, double amount) {
            if (!contains(mx, my)) return false;
            int ry = contentTop() - (int) scroll;
            for (Row r : rows) {
                int rh = r.height();
                if (my >= ry && my < ry + rh && r.scroll(amount)) return true;
                ry += rh;
            }
            scroll -= amount * 18;
            clampScroll();
            return true;
        }
    }
}
