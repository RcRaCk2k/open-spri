import type { About } from "./api/client";

/** Short status line shown in the header, e.g. "open-spri 1.0.0 · S/PRI 4.4 · zertifizierter Build". */
export function describeBuild(about: About): string {
  const certified = about.certifiedBuild ? "zertifizierter Build" : "nicht zertifizierter Build";
  return `${about.name} ${about.version} · S/PRI ${about.spriVersion} · ${certified}`;
}
