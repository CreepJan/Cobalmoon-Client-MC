/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.setting;

/** A number in [min, max], snapped to {@code step}. Shown as a slider. */
public final class NumberSetting extends Setting<Double> {
    private final double min;
    private final double max;
    private final double step;

    public NumberSetting(String key, String name, double defaultValue, double min, double max, double step) {
        super(key, name, check(defaultValue, min, max, step));
        this.min = min;
        this.max = max;
        this.step = step;
    }

    private static double check(double def, double min, double max, double step) {
        if (!(min < max) || step <= 0 || def < min || def > max) {
            throw new IllegalArgumentException("invalid number setting range");
        }
        return def;
    }

    public double min() { return min; }
    public double max() { return max; }
    public double step() { return step; }

    public int intValue() { return (int) Math.round(get()); }

    /** Position in [0, 1], for sliders. */
    public double fraction() { return (get() - min) / (max - min); }

    public void setFraction(double f) { set(min + Math.max(0, Math.min(1, f)) * (max - min)); }

    @Override
    protected Double sanitize(Double v) {
        if (v.isNaN()) return defaultValue();
        double clamped = Math.max(min, Math.min(max, v));
        double snapped = min + Math.round((clamped - min) / step) * step;
        // Avoid 0.30000000000000004-style noise in configs.
        double rounded = Math.round(snapped * 1e6) / 1e6;
        return Math.max(min, Math.min(max, rounded));
    }

    @Override public Object toJson() { return get(); }

    @Override
    public void fromJson(Object json) {
        set(json instanceof Number n ? n.doubleValue() : defaultValue());
    }
}
