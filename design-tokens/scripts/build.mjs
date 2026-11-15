// Generates platform outputs from design-tokens/tokens.json:
//   launcher/ui/src/lib/tokens.css                          (CSS custom properties)
//   api/src/main/java/dev/cobalmoon/api/theme/DefaultTokens.java (ARGB ints for the in-game UI)
//   docs/design-tokens.md                                   (reference table + contrast report)
// Usage: node design-tokens/scripts/build.mjs [--check]   (--check fails if outputs are stale)
import { mkdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { ROOT, loadTokens, entries, checkContrast, constName, MIN_RATIO } from "./lib.mjs";

const tokens = loadTokens();
const HEADER = "Generated from design-tokens/tokens.json by design-tokens/scripts/build.mjs. Do not edit by hand.";

function css() {
  const lines = [`/* ${HEADER} */`, ":root {"];
  for (const [k, t] of entries(tokens.color)) lines.push(`  --cm-color-${k}: ${t.$value};`);
  for (const [k, t] of entries(tokens.radius)) lines.push(`  --cm-radius-${k}: ${t.$value};`);
  for (const [k, t] of entries(tokens.space)) lines.push(`  --cm-space-${k}: ${t.$value};`);
  for (const [k, t] of entries(tokens.motion)) {
    const v = Array.isArray(t.$value) ? `cubic-bezier(${t.$value.join(", ")})` : t.$value;
    lines.push(`  --cm-motion-${k}: ${v};`);
  }
  for (const [k, t] of entries(tokens.font)) lines.push(`  --cm-font-${k}: ${t.$value};`);
  lines.push("}", "");
  lines.push("/* Reduced motion: honour the OS setting; the launcher can also force it with [data-reduce-motion]. */");
  lines.push("@media (prefers-reduced-motion: reduce) {", "  :root { --cm-motion-fast: 0ms; --cm-motion-normal: 0ms; --cm-motion-slow: 0ms; }", "}");
  lines.push(":root[data-reduce-motion] { --cm-motion-fast: 0ms; --cm-motion-normal: 0ms; --cm-motion-slow: 0ms; }", "");
  return lines.join("\n");
}

function java() {
  const fields = entries(tokens.color).map(([k, t]) => {
    const hex = t.$value.replace("#", "").toUpperCase();
    return `    /** ${t.$description ?? k} */\n    public static final int ${constName(k)} = 0xFF${hex};`;
  });
  const motion = entries(tokens.motion)
    .filter(([, t]) => t.$type === "duration")
    .map(([k, t]) => `    public static final int MOTION_${constName(k)}_MS = ${parseInt(t.$value, 10)};`);
  const keys = entries(tokens.color).map(([k]) => `        m.put("${k}", ${constName(k)});`);
  return `/*
 * ${HEADER}
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.theme;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Default Cobalmoon palette as opaque ARGB ints. Themes override these by key. */
public final class DefaultTokens {
    private DefaultTokens() {}

${fields.join("\n\n")}

${motion.join("\n")}

    /** All colour tokens by their token name (e.g. "accent"), in declaration order. */
    public static final Map<String, Integer> COLORS;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
${keys.join("\n")}
        COLORS = Collections.unmodifiableMap(m);
    }
}
`;
}

function markdown() {
  const rows = entries(tokens.color).map(([k, t]) => `| \`${k}\` | \`${t.$value}\` | ${t.$description ?? ""} |`);
  const contrast = checkContrast(tokens).map(
    (r) => `| \`${r.fg}\` on \`${r.bg}\` | ${r.ratio.toFixed(2)}:1 | ${r.level} (≥ ${MIN_RATIO[r.level]}) | ${r.ok ? "✅" : "❌"} |`,
  );
  return `<!-- ${HEADER} -->
# Design tokens

Single source of truth: [\`design-tokens/tokens.json\`](../design-tokens/tokens.json).
The launcher (CSS variables), the in-game UI (\`DefaultTokens.java\`) and these docs are generated from it.
The Cobalmoon palette is only the **default theme**: every colour can be overridden by a user theme.

## Colours

| Token | Value | Use |
|---|---|---|
${rows.join("\n")}

## Contrast (WCAG 2.x)

| Pair | Ratio | Required | Pass |
|---|---|---|---|
${contrast.join("\n")}

Rule of thumb: text on a turquoise (\`accent\`) surface is always \`on-accent\` (night blue), never stone.
`;
}

const outputs = {
  "launcher/ui/src/lib/tokens.css": css(),
  "api/src/main/java/dev/cobalmoon/api/theme/DefaultTokens.java": java(),
  "docs/design-tokens.md": markdown(),
};

const checkOnly = process.argv.includes("--check");
let stale = 0;
for (const [rel, content] of Object.entries(outputs)) {
  const path = resolve(ROOT, rel);
  const current = existsSync(path) ? readFileSync(path, "utf8").replace(/\r\n/g, "\n") : null;
  if (current === content) continue;
  if (checkOnly) {
    console.error(`stale: ${rel}`);
    stale++;
  } else {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, content);
    console.log(`wrote ${rel}`);
  }
}
if (checkOnly && stale) {
  console.error("Generated token files are out of date. Run: node design-tokens/scripts/build.mjs");
  process.exit(1);
}
