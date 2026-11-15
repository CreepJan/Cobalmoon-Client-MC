// Launcher theme: overrides of the design-token CSS variables (--cm-color-*), with WCAG checks.

/** Editable tokens and their Cobalmoon defaults (must match design-tokens/tokens.json). */
export const THEME_TOKENS: Record<string, { label: string; value: string }> = {
  night: { label: "Fondo", value: "#10182C" },
  "night-sunken": { label: "Barra lateral", value: "#0B1120" },
  "night-raised": { label: "Paneles", value: "#141D35" },
  "night-overlay": { label: "Resaltado", value: "#222E4F" },
  stone: { label: "Texto", value: "#E9EDF2" },
  lunar: { label: "Texto secundario", value: "#7486B8" },
  accent: { label: "Acento", value: "#52E0D0" },
  "accent-strong": { label: "Acento intenso", value: "#7FF0E3" },
  "on-accent": { label: "Texto sobre acento", value: "#10182C" },
};

const HEX = /^#[0-9a-fA-F]{6}$/;

export function isHex(v: string): boolean {
  return HEX.test(v);
}

/** Applies overrides to :root; invalid or unknown entries are ignored. Returns the applied map. */
export function applyTheme(overrides: Record<string, string>, root: HTMLElement = document.documentElement): Record<string, string> {
  const applied: Record<string, string> = {};
  for (const key of Object.keys(THEME_TOKENS)) {
    const v = overrides[key];
    if (v && isHex(v)) {
      root.style.setProperty(`--cm-color-${key}`, v);
      applied[key] = v;
    } else {
      root.style.removeProperty(`--cm-color-${key}`);
    }
  }
  return applied;
}

export function color(theme: Record<string, string>, key: string): string {
  return theme[key] && isHex(theme[key]) ? theme[key] : THEME_TOKENS[key].value;
}

function luminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/** Text/background pairs below WCAG AA (4.5:1; 3:1 for the accent used as a UI colour). */
export function contrastWarnings(theme: Record<string, string>): string[] {
  const pairs: [string, string, number][] = [
    ["stone", "night", 4.5],
    ["stone", "night-raised", 4.5],
    ["stone", "night-sunken", 4.5],
    ["lunar", "night", 4.5],
    ["on-accent", "accent", 4.5],
    ["accent", "night", 3],
  ];
  return pairs
    .map(([fg, bg, min]) => ({ fg, bg, min, r: contrast(color(theme, fg), color(theme, bg)) }))
    .filter((p) => p.r < p.min)
    .map((p) => `${THEME_TOKENS[p.fg].label} sobre ${THEME_TOKENS[p.bg].label.toLowerCase()}: ${p.r.toFixed(1)}:1 (mín. ${p.min})`);
}
