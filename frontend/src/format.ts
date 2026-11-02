import type { About } from "./api/client";

/** Short status line, e.g. "open-spri 1.0.0 · S/PRI 4.4 · zertifizierter Build". */
export function describeBuild(about: About): string {
  const certified = about.certifiedBuild ? "zertifizierter Build" : "nicht zertifizierter Build";
  return `${about.name} ${about.version} · S/PRI ${about.spriVersion} · ${certified}`;
}

const dateTime = new Intl.DateTimeFormat("de-DE", { dateStyle: "medium", timeStyle: "short", timeZone: "Europe/Berlin" });
const date = new Intl.DateTimeFormat("de-DE", { dateStyle: "medium", timeZone: "Europe/Berlin" });

/** "02.11.2026, 14:05" in German time; empty for missing values. */
export function formatDateTime(value: string | null | undefined): string {
  return value ? dateTime.format(new Date(value)) : "";
}

/** "02.11.2026" for an ISO date (YYYY-MM-DD) or instant. */
export function formatDate(value: string | null | undefined): string {
  if (!value) return "";
  return date.format(new Date(/^\d{4}-\d{2}-\d{2}$/.test(value) ? `${value}T12:00:00Z` : value));
}

/** Days from [today] to the ISO date [value]; negative when it has passed. */
export function daysUntil(value: string, today: Date = new Date()): number {
  const target = Date.UTC(Number(value.slice(0, 4)), Number(value.slice(5, 7)) - 1, Number(value.slice(8, 10)));
  const now = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate());
  return Math.round((target - now) / 86_400_000);
}

export const ROLE_LABELS: Record<string, string> = {
  ADMIN: "Administration",
  OPERATOR_AG: "Sachbearbeitung Auftraggeber",
  OPERATOR_LE: "Sachbearbeitung Leistungserbringer",
  VIEWER: "Nur lesen",
  INTEGRATION: "Fremdsystem",
  PARTNER: "Partner (Replikat)",
};
