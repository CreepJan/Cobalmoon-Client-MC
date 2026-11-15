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

    public HudEditor(ModuleManager modules, ClientSettings settings, CrosshairModule crosshair) {
        this.modules = modules;
        this.settings = settings;
        this.crosshair = crosshair;
    }

    public Set<HudElement> selection() { return selected; }

    public void init(int screenW, int screenH) {
        this.screenW = screenW;
        this.screenH = screenH;
        int pw = Math.max(104, Math.min(150, screenW / 5));
        left.bounds(4, 4, pw, screenH - 8);
        right.bounds(screenW - pw - 14, 4, pw + 10, screenH - 8);
        rebuildLeft();
        rebuildRight();
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
                for (Setting<?> s : m.settings()) rows.add(Widgets.forSetting(s));
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

    public void render(GuiCanvas c, int mx, int my) {
        lastCanvas = c;
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
                if (settings.editorHints.get()) {
                    String hint = Tr.of("cobalmoon.editor.hint");
                    int w = c.textWidth(hint) + 12;
                    int x = screenW / 2 - w / 2, y = 6;
                    if (!panelsVisible || (x > left.x + left.w && x + w < right.x)) {
                        c.fill(x, y, x + w, y + 16, Theme.panel());
                        c.centeredText(hint, screenW / 2, y + 4, Theme.muted(), false);
                    }
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

    public boolean keyPressed(int key, boolean shift) {
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
        CobalmoonClient.flushConfig();
    }
}
