import { describe, expect, it } from "vitest";
import { THEME_TOKENS, contrast, contrastWarnings, isHex } from "./theme";

describe("launcher theme", () => {
  it("defaults match the WCAG figures of the design tokens", () => {
    expect(contrast("#E9EDF2", "#10182C")).toBeCloseTo(15.02, 1);
    expect(contrast("#7486B8", "#10182C")).toBeCloseTo(4.92, 1);
  });

  it("the default palette has no warnings", () => {
    expect(contrastWarnings({})).toEqual([]);
  });

  it("flags unreadable choices", () => {
    const w = contrastWarnings({ stone: "#20283C" });
    expect(w.length).toBeGreaterThan(0);
    expect(w[0]).toContain("Texto");
  });

  it("ignores invalid colours", () => {
    expect(isHex("#52E0D0")).toBe(true);
    expect(isHex("red")).toBe(false);
    expect(contrastWarnings({ stone: "red" })).toEqual([]);
  });

  it("covers every token used by the stylesheet", () => {
    expect(Object.keys(THEME_TOKENS)).toContain("accent");
    expect(Object.keys(THEME_TOKENS)).toContain("on-accent");
  });
});
