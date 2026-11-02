/** German labels for the states and terms of the order API (docs/api/auftraege.md). */

export const PHASE_LABELS: Record<string, string> = {
  DRAFT: "Entwurf",
  NOT_STARTED: "nicht begonnen",
  SUBMITTED: "gesendet / empfangen",
  ACKNOWLEDGED: "bestätigt (QEB)",
  CONFIRMED: "Termin bestätigt (ABM)",
  APPOINTMENT_REQUESTED: "Terminabstimmung (TAM)",
  COMPLETED: "ausgeführt (ERLM)",
  CLOSED: "abgeschlossen (ENTM)",
  ABORTED: "abgebrochen (ABBM)",
};

export const PHASE_COLORS: Record<string, string> = {
  DRAFT: "gray", NOT_STARTED: "gray", SUBMITTED: "blue", ACKNOWLEDGED: "cyan", CONFIRMED: "teal",
  APPOINTMENT_REQUESTED: "orange", COMPLETED: "green", CLOSED: "dark", ABORTED: "red",
};

export const ROLE_NAMES: Record<string, string> = {
  AUFTRAGGEBER: "Auftraggeber",
  LEISTUNGSERBRINGER: "Leistungserbringer",
  ABGEBENDER_EKP: "abgebender EKP",
};

export const BUSINESS_CASES = ["NEU", "KUE-AG", "KUE-LE", "AEN-LMAE", "LAE", "PV", "EST", "DIAG-GET", "DIAG-SET"] as const;

export const REQUEST_LABELS: Record<string, string> = {
  STORNO: "Storno",
  TERMINVERSCHIEBUNG: "Terminverschiebung",
  REKLAMATION: "Reklamation",
};

export const HANDOVER_LABELS: Record<string, string> = {
  ANNOUNCED: "AKM-PV erhalten",
  REPLIED: "RUEM-PV gesendet",
  CONFIRMED: "ABM-PV erhalten",
  COMPLETED: "ERLM-PV erhalten",
  CLOSED: "ENTM-PV erhalten",
  ABORTED: "ABBM-PV erhalten",
};

export function phaseLabel(phase: string): string {
  return PHASE_LABELS[phase] ?? phase;
}

/** Message types with attributes the dialog offers as form fields; everything else goes into the JSON field. */
export const DATE_ATTRIBUTES: Record<string, { name: string; label: string }[]> = {
  ABM: [{ name: "verbindlicherLiefertermin", label: "Verbindlicher Liefertermin" }],
};
