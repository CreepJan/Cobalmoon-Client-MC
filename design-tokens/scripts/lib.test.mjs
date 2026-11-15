import { test } from "node:test";
import assert from "node:assert/strict";
import { contrast, hexToRgb, loadTokens, checkContrast } from "./lib.mjs";

test("hexToRgb parses with and without #", () => {
  assert.deepEqual(hexToRgb("#52E0D0"), [82, 224, 208]);
  assert.deepEqual(hexToRgb("10182c"), [16, 24, 44]);
  assert.throws(() => hexToRgb("#fff"));
});

test("contrast matches WCAG reference values", () => {
  assert.equal(contrast("#000000", "#FFFFFF").toFixed(2), "21.00");
  assert.equal(contrast("#FFFFFF", "#FFFFFF").toFixed(2), "1.00");
  // Order must not matter.
  assert.equal(contrast("#10182C", "#E9EDF2"), contrast("#E9EDF2", "#10182C"));
});

test("official palette meets the ratios stated in the architecture doc", () => {
  assert.ok(contrast("#E9EDF2", "#10182C") > 14);
  assert.ok(contrast("#52E0D0", "#10182C") > 10);
  assert.ok(contrast("#7486B8", "#10182C") >= 4.5);
  assert.ok(contrast("#E9EDF2", "#52E0D0") < 3, "stone on accent must stay forbidden");
});

test("every declared pair in tokens.json passes", () => {
  const failing = checkContrast(loadTokens()).filter((r) => !r.ok);
  assert.deepEqual(failing, []);
});
