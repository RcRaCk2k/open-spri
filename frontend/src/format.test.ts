import { describe, expect, it } from "vitest";
import { describeBuild } from "./format";

describe("describeBuild", () => {
  it("marks certified builds", () => {
    expect(
      describeBuild({ name: "open-spri", version: "1.0.0", spriVersion: "4.4", certifiedBuild: true }),
    ).toBe("open-spri 1.0.0 · S/PRI 4.4 · zertifizierter Build");
  });

  it("marks other builds", () => {
    expect(
      describeBuild({ name: "open-spri", version: "0.1.0", spriVersion: "4.4", certifiedBuild: false }),
    ).toContain("nicht zertifizierter Build");
  });
});
