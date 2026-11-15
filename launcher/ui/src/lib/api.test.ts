import { describe, expect, it } from "vitest";
import { overallProgress, errorText } from "./api";

describe("overallProgress", () => {
  it("is monotonic across stages", () => {
    const a = overallProgress({ stage: "libraries", done: 10, total: 10 });
    const b = overallProgress({ stage: "assets", done: 0, total: 3000 });
    expect(b).toBeGreaterThanOrEqual(a);
  });

  it("reaches 1 when starting is done", () => {
    expect(overallProgress({ stage: "starting", done: 1, total: 1 })).toBeCloseTo(1, 5);
  });

  it("handles empty totals", () => {
    expect(overallProgress({ stage: "metadata", done: 0, total: 0 })).toBe(0);
  });
});

describe("errorText", () => {
  it("passes strings through", () => {
    expect(errorText("boom")).toBe("boom");
    expect(errorText(new Error("x"))).toBe("x");
  });
});
