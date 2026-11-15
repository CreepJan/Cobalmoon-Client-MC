// Fails (exit 1) when any declared foreground/background pair misses its WCAG level.
import { loadTokens, checkContrast, MIN_RATIO } from "./lib.mjs";

const results = checkContrast(loadTokens());
let failed = 0;
for (const r of results) {
  const mark = r.ok ? "ok  " : "FAIL";
  if (!r.ok) failed++;
  console.log(`${mark} ${r.fg.padEnd(14)} on ${r.bg.padEnd(14)} ${r.ratio.toFixed(2).padStart(5)}:1  (needs ${MIN_RATIO[r.level]}:1, ${r.level})`);
}
if (failed) {
  console.error(`\n${failed} contrast pair(s) below the required WCAG level.`);
  process.exit(1);
}
console.log(`\nAll ${results.length} pairs pass.`);
