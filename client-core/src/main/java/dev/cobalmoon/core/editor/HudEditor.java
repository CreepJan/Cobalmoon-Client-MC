/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.editor;

import dev.cobalmoon.api.hud.HudElement;
import dev.cobalmoon.api.hud.HudLayout;
import dev.cobalmoon.api.module.Category;
import dev.cobalmoon.api.module.Module;
import dev.cobalmoon.api.setting.Setting;
import dev.cobalmoon.api.theme.DefaultTokens;
import dev.cobalmoon.core.ClientSettings;
import dev.cobalmoon.core.CobalmoonClient;
import dev.cobalmoon.core.module.ModuleManager;
import dev.cobalmoon.core.modules.CrosshairModule;
import dev.cobalmoon.core.render.GuiCanvas;
import dev.cobalmoon.core.render.HudRenderer;
import dev.cobalmoon.core.render.HudRenderer.Bounds;
import dev.cobalmoon.core.ui.ColorPicker;
import dev.cobalmoon.core.ui.Theme;
import dev.cobalmoon.core.ui.Tr;
import dev.cobalmoon.core.ui.Widgets;
import dev.cobalmoon.core.ui.Widgets.Panel;
import dev.cobalmoon.core.ui.Widgets.Row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HUD editor and module menu, independent of the Minecraft version: each version's screen class
 * forwards drawing and input here.
 *
 * <ul>
 *   <li>Left panel: modules by category, with on/off switches.</li>
 *   <li>Centre: the live HUD; drag to move (with snapping), mouse wheel to scale, shift-click to multi-select,
 *       arrow keys to nudge.</li>
 *   <li>Right panel: style of the selected element and settings of its module.</li>
 * </ul>
 */
public final class HudEditor {
    private static final int GLFW_KEY_LEFT = 263, GLFW_KEY_RIGHT = 262, GLFW_KEY_UP = 265, GLFW_KEY_DOWN = 264,
            GLFW_KEY_R = 82, GLFW_KEY_TAB = 258;
    private boolean panelsVisible = true;

    private final ModuleManager modules;
    private final ClientSettings settings;
    private final CrosshairModule crosshair;
    private final Panel left = new Panel(() -> Tr.of("cobalmoon.editor.modules"));
    private final Panel right = new Panel(() -> Tr.of("cobalmoon.editor.settings"));
    private final Set<HudElement> selected = new LinkedHashSet<>();
    private Module selectedModule;
    private int screenW, screenH;
    private final long openedAt = System.nanoTime();

    // drag state
    private boolean dragging;
    private double dragStartX, dragStartY;
    private HudElement dragLead;
    private final Map<HudElement, Bounds> dragOrigin = new HashMap<>();
    private List<Snapping.Guide> guides = List.of();
    private Panel activePanel;
    private GuiCanvas lastCanvas;

    // colour picker popup
    private ColorPicker picker;
    private boolean pickerDrag;

    // "edit layout" / "back to settings" button at the top centre
    private Bounds modeButton = new Bounds(0, 0, 0, 0);

    // corner resize handle
    private static final int HANDLE = 6;
    private HudElement resizing;
    private Bounds resizeStart;
    private float resizeStartScale;

    public HudEditor(ModuleManager modules, ClientSettings settings, CrosshairModule crosshair) {
        this.modules = modules;
        this.settings = settings;
        this.crosshair = crosshair;
    }

    public Set<HudElement> selection() { return selected; }

    public void init(int screenW, int screenH) {
        this.screenW = screenW;
        this.screenH = screenH;
        // Provisional size; fitPanels() sizes the panels to their content on every frame.
        left.bounds(4, 4, 120, screenH - 8);
        right.bounds(screenW - 124, 4, 120, screenH - 8);
        Widgets.setPickerOpener((title, get, set, rowY) -> openPicker(title, get, set, rowY));
        rebuildLeft();
        rebuildRight();
    }

    /** Opens the colour wheel next to the settings panel. */
    public void openPicker(String title, java.util.function.IntSupplier get, java.util.function.IntConsumer set, int rowY) {
        picker = new ColorPicker(title, get, set);
        int px = Math.max(4, right.x - ColorPicker.WIDTH - 6);
        int py = Math.max(4, Math.min(screenH - ColorPicker.HEIGHT - 4, rowY - 20));
        picker.position(px, py);
    }

    public ColorPicker picker() { return picker; }

    /** Switches between the full editor and the clean "move and resize" view. */
    public void setLayoutMode(boolean layout) {
        panelsVisible = !layout;
        picker = null;
        Widgets.TextField.blur();
    }

    public Bounds modeButton() { return modeButton; }

    /** On-screen bounds of an element as last drawn by the editor (null before the first frame). */
    public Bounds boundsOf(HudElement e) {
        return lastCanvas == null ? null : HudRenderer.bounds(e, lastCanvas, true);
    }

    private Bounds handleOf(Bounds b) {
        return new Bounds(b.right() - HANDLE + 2, b.bottom() - HANDLE + 2, HANDLE, HANDLE);
    }

    // ---- panels ---------------------------------------------------------------------------

    private void rebuildLeft() {
        List<Row> rows = new ArrayList<>();
        for (Category cat : Category.values()) {
            List<Module> inCat = modules.all().stream().filter(m -> m.category() == cat).toList();
            if (inCat.isEmpty()) continue;
            rows.add(new Widgets.Header(() -> Tr.of(cat.translationKey())));
            for (Module m : inCat) {
                rows.add(new Widgets.ModuleRow(() -> Tr.of(m.name()), m::isEnabled, () -> {
                    m.toggle();
                    if (!m.isEnabled()) selected.removeIf(e -> e.owner() == m);
                }, () -> selectModule(m), () -> m == selectedModule));
            }
        }
        left.setRows(rows);
    }

    private void selectModule(Module m) {
        selectedModule = m;
        selected.clear();
        if (m.isEnabled()) selected.addAll(m.hudElements());
        rebuildRight();
    }

    private void rebuildRight() {
        List<Row> rows = new ArrayList<>();
        HudElement el = selected.size() == 1 ? selected.iterator().next() : null;
        if (selected.size() > 1) {
            rows.add(new Widgets.Label(() -> Tr.of("cobalmoon.editor.multi", selected.size())));
            rows.add(new Widgets.Button(() -> Tr.of("cobalmoon.editor.reset_position"), () -> {
                for (HudElement e : selected) resetLayout(e);
            }, false));
        } else if (el != null) {
            HudLayout l = el.layout();
            rows.add(new Widgets.Header(() -> Tr.of(el.name())));
            rows.add(new Widgets.Slider(() -> Tr.of("cobalmoon.editor.scale"),
                    () -> (l.scale - HudLayout.MIN_SCALE) / (HudLayout.MAX_SCALE - HudLayout.MIN_SCALE),
                    f -> { l.setScale((float) (HudLayout.MIN_SCALE + f * (HudLayout.MAX_SCALE - HudLayout.MIN_SCALE))); dirty(el); },
                    () -> String.format(java.util.Locale.ROOT, "%.2fx", l.scale)));
            rows.add(new Widgets.Slider(() -> Tr.of("cobalmoon.editor.opacity"),
                    () -> (l.opacity - 0.1) / 0.9, f -> { l.setOpacity((float) (0.1 + f * 0.9)); dirty(el); },
                    () -> Math.round(l.opacity * 100) + "%"));
            if (el.supportsBackground()) {
                rows.add(new Widgets.Toggle(() -> Tr.of("cobalmoon.editor.background"), () -> l.background, v -> { l.background = v; dirty(el); }));
            }
            rows.add(new Widgets.ColorPick(() -> Tr.of("cobalmoon.editor.background_color"), () -> l.backgroundColor, v -> { l.backgroundColor = v; dirty(el); }));
            rows.add(new Widgets.ColorPick(() -> Tr.of("cobalmoon.editor.text_color"), () -> l.textColor, v -> { l.textColor = v; dirty(el); }));
            rows.add(new Widgets.ColorPick(() -> Tr.of("cobalmoon.editor.accent_color"), () -> l.accentColor, v -> { l.accentColor = v; dirty(el); }));
            rows.add(new Widgets.Toggle(() -> Tr.of("cobalmoon.editor.shadow"), () -> l.shadow, v -> { l.shadow = v; dirty(el); }));
            rows.add(new Widgets.Button(() -> Tr.of("cobalmoon.editor.reset_position"), () -> resetLayout(el), false));
        }
        Module m = el != null ? el.owner() : selectedModule;
        if (m != null && selected.size() <= 1) {
            if (!m.settings().isEmpty()) {
                rows.add(new Widgets.Header(() -> Tr.of(m.name())));
                rows.add(new Widgets.Label(() -> Tr.of(m.description())));
                for (Setting<?> s : m.settings()) {
                    if (!s.isHidden()) rows.add(Widgets.forSetting(s));
                }
            } else if (el == null) {
                rows.add(new Widgets.Header(() -> Tr.of(m.name())));
                rows.add(new Widgets.Label(() -> Tr.of("cobalmoon.editor.no_settings")));
            }
            if (!m.isEnabled()) rows.add(new Widgets.Label(() -> Tr.of("cobalmoon.editor.module_off")));
        }
        if (rows.isEmpty()) {
            rows.add(new Widgets.Header(() -> Tr.of("cobalmoon.editor.client")));
            for (Setting<?> s : settings.all()) rows.add(Widgets.forSetting(s));
            rows.add(new Widgets.Label(() -> Tr.of("cobalmoon.editor.select_hint")));
        }
        right.setRows(rows);
    }

    private void resetLayout(HudElement e) {
        e.resetLayout();
        dirty(e);
    }

    private void dirty(HudElement e) {
        if (e.owner() != null) modules.markDirty(e.owner());
    }

    // ---- rendering ------------------------------------------------------------------------

    private List<HudElement> editable() {
        return modules.hudElements().stream().filter(e -> e.owner() == null || e.owner().isEnabled()).toList();
    }

    private float fade() {
        if (settings.reduceMotion.get()) return 1f;
        float t = (System.nanoTime() - openedAt) / 1_000_000f / DefaultTokens.MOTION_NORMAL_MS;
        t = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - t) * (1f - t) * (1f - t); // ease-out cubic
    }

    /** Sizes both panels to their content (up to a third of the screen each) and wraps long texts. */
    private void fitPanels(GuiCanvas c) {
        int min = 104, max = Math.max(min, screenW * 32 / 100);
        int lw = left.preferredWidth(c, min, max);
        int rw = right.preferredWidth(c, min, max);
        left.bounds(4, 4, lw, screenH - 8);
        right.bounds(screenW - rw - 4, 4, rw, screenH - 8);
        left.layoutRows(c);
        right.layoutRows(c);
    }

    public void render(GuiCanvas c, int mx, int my) {
        lastCanvas = c;
        fitPanels(c);
        float prev = c.setAlpha(fade());
        try {
            c.fill(0, 0, screenW, screenH, Theme.scrim());
            if (dragging) {
                int faint = Theme.withAlpha(DefaultTokens.LUNAR, 0x30);
                c.fill(screenW / 2, 0, screenW / 2 + 1, screenH, faint);
                c.fill(0, screenH / 2, screenW, screenH / 2 + 1, faint);
            }
            if (crosshair.isEnabled()) crosshair.draw(c, screenW / 2, screenH / 2);

            HudElement hovered = dragging ? null : elementAt(c, mx, my);
            for (HudElement e : editable()) {
                HudRenderer.render(e, c, true, t -> CobalmoonClient.LOG.warn("HUD element {} failed to render", e.id(), t));
                Bounds b = HudRenderer.bounds(e, c, true);
                int color = selected.contains(e) ? Theme.accent() : e == hovered ? Theme.text() : Theme.withAlpha(DefaultTokens.LUNAR, 0x99);
                c.outline(b.x() - 1, b.y() - 1, b.w() + 2, b.h() + 2, color);
                if (selected.size() == 1 && selected.contains(e)) {
                    // resize handle (bottom-right corner)
                    Bounds h = handleOf(b);
                    c.fill(h.x(), h.y(), h.right(), h.bottom(), Theme.accent());
                    c.outline(h.x(), h.y(), h.w(), h.h(), Theme.onAccent());
                }
            }
            for (Snapping.Guide g : guides) {
                if (g.vertical()) c.fill(g.pos(), 0, g.pos() + 1, screenH, Theme.accent());
                else c.fill(0, g.pos(), screenW, g.pos() + 1, Theme.accent());
            }
            if (!dragging) {
                c.nextLayer(); // panels must cover the HUD's text, not only its backgrounds
                if (panelsVisible) {
                    left.render(c, mx, my);
                    right.render(c, mx, my);
                }
                // Mode button: "Move & resize HUD" ⇄ "Back to settings".
                String label = Tr.of(panelsVisible ? "cobalmoon.editor.layout_mode" : "cobalmoon.editor.back_to_settings");
                int bw = c.textWidth(label) + 16, bx = screenW / 2 - bw / 2, by = 6;
                modeButton = new Bounds(bx, by, bw, 16);
                boolean hov = modeButton.contains(mx, my);
                c.fill(bx, by, bx + bw, by + 16, hov ? Theme.accentStrong() : Theme.accent());
                c.centeredText(label, screenW / 2, by + 4, Theme.onAccent(), false);

                if (settings.editorHints.get() && !panelsVisible) {
                    // Split the hint at " · " into as many lines as needed to fit the screen.
                    List<String> lines = new ArrayList<>();
                    StringBuilder line = new StringBuilder();
                    for (String part : Tr.of("cobalmoon.editor.hint").split(" · ")) {
                        String cand = line.isEmpty() ? part : line + " · " + part;
                        if (!line.isEmpty() && c.textWidth(cand) > screenW - 24) {
                            lines.add(line.toString());
                            line.setLength(0);
                            line.append(part);
                        } else {
                            line.setLength(0);
                            line.append(cand);
                        }
                    }
                    lines.add(line.toString());
                    int w = 0;
                    for (String l : lines) w = Math.max(w, c.textWidth(l));
                    w += 12;
                    int h = lines.size() * 10 + 6, x = screenW / 2 - w / 2, y = screenH - h - 4;
                    c.fill(x, y, x + w, y + h, Theme.panel());
                    for (int i = 0; i < lines.size(); i++) c.centeredText(lines.get(i), screenW / 2, y + 4 + i * 10, Theme.muted(), false);
                }
                if (picker != null) {
                    c.nextLayer();
                    picker.render(c, mx, my);
                }
            }
        } finally {
            c.setAlpha(prev);
        }
    }

    private HudElement elementAt(GuiCanvas c, double mx, double my) {
        List<HudElement> list = editable();
        for (int i = list.size() - 1; i >= 0; i--) {
            HudElement e = list.get(i);
            if (HudRenderer.bounds(e, c, true).contains(mx, my)) return e;
        }
        return null;
    }

    // ---- input ----------------------------------------------------------------------------

    public boolean mouseClicked(double mx, double my, int button, boolean shift) {
        GuiCanvas c = lastCanvas;
        if (c == null) return false;
        Widgets.TextField.blur(); // a click on a text field focuses it again below
        if (picker != null) {
            if (picker.click(mx, my)) {
                pickerDrag = true;
                return true;
            }
            picker = null; // click outside (or "Done") closes it
            if (!panelsVisible || !(left.contains(mx, my) || right.contains(mx, my))) return true;
        }
        if (modeButton.contains(mx, my)) {
            setLayoutMode(panelsVisible);
            return true;
        }
        if (selected.size() == 1 && button == 0) {
            HudElement only = selected.iterator().next();
            Bounds b = HudRenderer.bounds(only, c, true);
            Bounds h = handleOf(b);
            if (mx >= h.x() - 2 && mx < h.right() + 2 && my >= h.y() - 2 && my < h.bottom() + 2) {
                resizing = only;
                resizeStart = b;
                resizeStartScale = only.layout().scale;
                return true;
            }
        }
        for (Panel p : new Panel[]{left, right}) {
            if (panelsVisible && p.contains(mx, my)) {
                activePanel = p;
                p.click(mx, my, button);
                // A toggle in the left panel may change what is selectable.
                if (p == left) rebuildRight();
                return true;
            }
        }
        HudElement hit = elementAt(c, mx, my);
        if (hit == null) {
            if (!shift) {
                selected.clear();
                selectedModule = null;
                rebuildRight();
            }
            return true;
        }
        if (shift) {
            if (!selected.remove(hit)) selected.add(hit);
        } else if (!selected.contains(hit)) {
            selected.clear();
            selected.add(hit);
        }
        selectedModule = hit.owner();
        rebuildRight();
        if (button == 0 && selected.contains(hit)) {
            dragging = true;
            dragLead = hit;
            dragStartX = mx;
            dragStartY = my;
            dragOrigin.clear();
            for (HudElement e : selected) dragOrigin.put(e, HudRenderer.bounds(e, c, true));
        }
        return true;
    }

    public boolean mouseDragged(double mx, double my) {
        if (pickerDrag && picker != null) {
            picker.drag(mx, my);
            return true;
        }
        if (resizing != null && lastCanvas != null) {
            // Scale so the bottom-right corner follows the mouse; the top-left corner stays put.
            double sx = (mx - resizeStart.x()) / Math.max(1.0, resizeStart.w());
            double sy = (my - resizeStart.y()) / Math.max(1.0, resizeStart.h());
            float factor = (float) Math.max(sx, sy);
            HudLayout l = resizing.layout();
            l.setScale(resizeStartScale * factor);
            Bounds now = HudRenderer.bounds(resizing, lastCanvas, true);
            l.moveTo(resizeStart.x(), resizeStart.y(), now.w(), now.h(), screenW, screenH);
            return true;
        }
        if (activePanel != null) {
            activePanel.drag(mx);
            return true;
        }
        if (!dragging || lastCanvas == null) return false;
        int dx = (int) Math.round(mx - dragStartX), dy = (int) Math.round(my - dragStartY);
        Bounds lead = dragOrigin.get(dragLead);
        int nx = lead.x() + dx, ny = lead.y() + dy;
        guides = List.of();
        if (settings.snapping.get()) {
            List<Bounds> others = new ArrayList<>();
            for (HudElement e : editable()) if (!selected.contains(e)) others.add(HudRenderer.bounds(e, lastCanvas, true));
            Snapping.Result r = Snapping.snap(nx, ny, lead.w(), lead.h(), screenW, screenH, others, Snapping.DEFAULT_THRESHOLD);
            nx = r.x();
            ny = r.y();
            guides = r.guides();
        }
        int ddx = nx - lead.x(), ddy = ny - lead.y();
        for (var en : dragOrigin.entrySet()) {
            Bounds b = en.getValue();
            en.getKey().layout().moveTo(b.x() + ddx, b.y() + ddy, b.w(), b.h(), screenW, screenH);
        }
        return true;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (pickerDrag) {
            pickerDrag = false;
            if (picker != null) picker.release();
        }
        if (resizing != null) {
            dirty(resizing);
            resizing = null;
        }
        if (activePanel != null) {
            activePanel.release();
            activePanel = null;
        }
        if (dragging) {
            dragging = false;
            guides = List.of();
            for (HudElement e : dragOrigin.keySet()) dirty(e);
            dragOrigin.clear();
        }
        return true;
    }

    public boolean mouseScrolled(double mx, double my, double amount) {
        if (panelsVisible && (left.scroll(mx, my, amount) || right.scroll(mx, my, amount))) return true;
        GuiCanvas c = lastCanvas;
        if (c == null) return false;
        HudElement hit = elementAt(c, mx, my);
        if (hit == null) return false;
        Set<HudElement> targets = selected.contains(hit) ? selected : Set.of(hit);
        for (HudElement e : targets) {
            Bounds before = HudRenderer.bounds(e, c, true);
            e.layout().setScale(e.layout().scale + (amount > 0 ? 0.05f : -0.05f));
            // keep the element's centre in place while scaling
            Bounds after = HudRenderer.bounds(e, c, true);
            e.layout().moveTo(before.x() + before.w() / 2 - after.w() / 2, before.y() + before.h() / 2 - after.h() / 2,
                    after.w(), after.h(), screenW, screenH);
            dirty(e);
        }
        return true;
    }

    public boolean panelsVisible() { return panelsVisible; }

    /** Characters typed while a text field has focus. */
    public boolean charTyped(String text) {
        return Widgets.TextField.type(text);
    }

    public boolean keyPressed(int key, boolean shift) {
        if (Widgets.TextField.key(key)) return true;
        if (picker != null && (key == 256 || key == 257)) { // Esc / Enter close the colour picker
            picker = null;
            return true;
        }
        if (key == GLFW_KEY_TAB) {
            panelsVisible = !panelsVisible;
            return true;
        }
        if (selected.isEmpty() || lastCanvas == null) return false;
        int step = shift ? 10 : 1;
        int dx = key == GLFW_KEY_LEFT ? -step : key == GLFW_KEY_RIGHT ? step : 0;
        int dy = key == GLFW_KEY_UP ? -step : key == GLFW_KEY_DOWN ? step : 0;
        if (key == GLFW_KEY_R) {
            selected.forEach(this::resetLayout);
            return true;
        }
        if (dx == 0 && dy == 0) return false;
        for (HudElement e : selected) {
            Bounds b = HudRenderer.bounds(e, lastCanvas, true);
            e.layout().moveTo(b.x() + dx, b.y() + dy, b.w(), b.h(), screenW, screenH);
            dirty(e);
        }
        return true;
    }

    /** Called when the screen closes: persist everything now. */
    public void close() {
        Widgets.TextField.blur();
        CobalmoonClient.flushConfig();
    }
}
