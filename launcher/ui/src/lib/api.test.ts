import { describe, expect, it } from "vitest";
import { overallProgress, errorText, samePath, loaderLabel, loaderLaunchable, formatDownloads } from "./api";

describe("import helpers", () => {
  it("compares paths across separators, case and trailing slashes", () => {
    expect(samePath("C:\\Users\\a\\.minecraft\\", "c:/users/a/.minecraft")).toBe(true);
    expect(samePath("/home/a/x", "/home/a/y")).toBe(false);
    expect(samePath(null, "/x")).toBe(false);
  });

  it("labels loaders and knows which can launch", () => {
    expect(loaderLabel({ kind: "fabric", version: "0.20.1" })).toBe("Fabric 0.20.1");
    expect(loaderLabel({ kind: "unknown" })).toBe("Desconocido");
    expect(loaderLaunchable({ kind: "neoforge", version: "21.1" })).toBe(false);
    expect(loaderLaunchable({ kind: "quilt", version: null })).toBe(true);
  });

  it("formats download counts", () => {
    expect(formatDownloads(1_234_567)).toBe("1.2 M");
    expect(formatDownloads(45_600)).toBe("46 k");
    expect(formatDownloads(12)).toBe("12");
  });
});

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
