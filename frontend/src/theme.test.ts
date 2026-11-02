import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? sources(path) : /\.tsx?$/.test(name) && !name.endsWith(".test.ts") ? [path] : [];
  });
}

describe("colors come from the theme", () => {
  it("no text color with a fixed shade (e.g. red.8): the theme keeps them at WCAG AA in both schemes", () => {
    const offenders = sources("src").flatMap((file) =>
      readFileSync(file, "utf8").split("\n").flatMap((line, i) => (/\bc="[a-z]+(\.\d)?"/.test(line) && !/c="(dimmed|inherit)"/.test(line) ? [`${file}:${i + 1}`] : [])),
    );
    expect(offenders).toEqual([]);
  });
});
