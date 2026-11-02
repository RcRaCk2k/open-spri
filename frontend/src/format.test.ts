import { describe, expect, it } from "vitest";
import { daysUntil, describeBuild, formatDate } from "./format";

describe("describeBuild", () => {
  it("marks certified builds", () => {
    expect(describeBuild({ name: "open-spri", version: "1.0.0", spriVersion: "4.4", certifiedBuild: true })).toBe("open-spri 1.0.0 · S/PRI 4.4 · zertifizierter Build");
  });

  it("marks other builds", () => {
    expect(describeBuild({ name: "open-spri", version: "0.1.0", spriVersion: "4.4", certifiedBuild: false })).toContain("nicht zertifizierter Build");
  });
});

describe("dates", () => {
  it("counts days to a deadline, negative when passed", () => {
    const today = new Date(2026, 10, 2, 23, 30);
    expect(daysUntil("2026-11-02", today)).toBe(0);
    expect(daysUntil("2026-11-05", today)).toBe(3);
    expect(daysUntil("2026-10-31", today)).toBe(-2);
  });

  it("formats ISO dates in German", () => {
    expect(formatDate("2026-11-02")).toBe("02.11.2026");
    expect(formatDate(null)).toBe("");
  });
});
