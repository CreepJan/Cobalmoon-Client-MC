// Shared helpers for the Cobalmoon design-token pipeline. No dependencies.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
export const TOKENS_PATH = resolve(ROOT, "design-tokens", "tokens.json");

export function loadTokens(path = TOKENS_PATH) {
  return JSON.parse(readFileSync(path, "utf8"));
}

/** Returns [[name, token]] for every token in a group, skipping $-prefixed metadata keys. */
export function entries(group) {
  return Object.entries(group ?? {}).filter(([k]) => !k.startsWith("$"));
}

export function hexToRgb(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) throw new Error(`Invalid hex color: ${hex}`);
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/** WCAG 2.x relative luminance. */
export function luminance(hex) {
  const [r, g, b] = hexToRgb(hex).map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG 2.x contrast ratio between two hex colors (1..21). */
export function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

export const MIN_RATIO = { AA: 4.5, AAA: 7, UI: 3 };

/** Checks every declared pair. Returns [{fg, bg, level, ratio, ok}]. */
export function checkContrast(tokens) {
  const colors = Object.fromEntries(entries(tokens.color).map(([k, t]) => [k, t.$value]));
  return (tokens.$contrast?.pairs ?? []).map(({ fg, bg, level }) => {
    if (!colors[fg] || !colors[bg]) throw new Error(`Unknown color in contrast pair: ${fg} / ${bg}`);
    const ratio = contrast(colors[fg], colors[bg]);
    const min = MIN_RATIO[level];
    if (!min) throw new Error(`Unknown contrast level: ${level}`);
    return { fg, bg, level, ratio, ok: ratio >= min };
  });
}

export function camel(name) {
  return name.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
}

export function constName(name) {
  return name.replace(/-/g, "_").toUpperCase();
}
