import { hasScope, Scope, type ScopeName } from "./auth/scopes";

export interface NavEntry {
  path: string;
  label: string;
  group: "Übersicht" | "Aufträge" | "Stammdaten" | "Betrieb" | "Verwaltung";
  /** shown only with this scope */
  scope?: ScopeName;
}

/** All pages of the web interface (AUFGABE 8.6). */
export const NAVIGATION: readonly NavEntry[] = [
  { path: "/", label: "Dashboard", group: "Übersicht" },
  { path: "/orders", label: "Aufträge", group: "Aufträge", scope: Scope.ordersRead },
  { path: "/work-queue", label: "Arbeitsvorrat LE", group: "Aufträge", scope: Scope.ordersRead },
  { path: "/pv-requests", label: "Providerwechsel-Anfragen", group: "Aufträge", scope: Scope.ordersRead },
  { path: "/availability", label: "Verfügbarkeit", group: "Aufträge", scope: Scope.availabilityRead },
  { path: "/inventory", label: "Bestand", group: "Aufträge", scope: Scope.ordersRead },
  { path: "/partners", label: "Partner", group: "Stammdaten", scope: Scope.partnersRead },
  { path: "/products", label: "Produkte", group: "Stammdaten", scope: Scope.partnersRead },
  { path: "/message-codes", label: "Meldungscodes", group: "Stammdaten", scope: Scope.ordersRead },
  { path: "/audit", label: "Audit-Log", group: "Verwaltung", scope: Scope.auditRead },
  { path: "/users", label: "Benutzer und Tokens", group: "Verwaltung", scope: Scope.usersAdmin },
];

/** The entries the user may see, grouped in the order of [NAVIGATION]. */
export function visibleNavigation(scopes: readonly string[]): Map<NavEntry["group"], NavEntry[]> {
  const groups = new Map<NavEntry["group"], NavEntry[]>();
  for (const entry of NAVIGATION) {
    if (!hasScope(scopes, entry.scope)) continue;
    groups.set(entry.group, [...(groups.get(entry.group) ?? []), entry]);
  }
  return groups;
}
