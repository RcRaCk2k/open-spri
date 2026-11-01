# ADR-0002: Scope bestätigt, Diagnose in v1, Betrieb hinter vorhandenem nginx

Status: angenommen | Datum: 2026-11-01

## Kontext

ADR-0001 hat die Vorschläge D-10 bis D-14 offen gelassen und Diagnose (DIAG-GET/SET) für v2 vorgesehen. Der Auftraggeber hat vorgegeben: Diagnose kommt nur dann in v2, wenn ein späteres Nachrüsten keine zusätzlichen Prüfkosten verursacht.

Prüfung: Ein optionaler Geschäftsfall, der später unterstützt werden soll, braucht einen erneuten Konformitätstest. Laut Spezifikation 10.5 lässt der Provider optionale GF zertifizieren, wenn er sie unterstützt, und laut ak-spri.de muss ein Zertifikat je Version einmal beantragt werden. Den KFT führt Enghouse gegen eine Servicegebühr durch. Ein Nachrüsten würde also erneut Prüfkosten verursachen.

## Entscheidung

- **DIAG-GET/SET gehört zu v1** (D-03 geändert). Die Umsetzung steht in `docs/AUFGABE-SPRI.md`, Kap. 5.11.
- **D-10 bis D-14 sind angenommen:** alle optionalen Testfälle umsetzen; Auftragsklammer ohne automatische Terminkoordination; Single-Tenant mit vorbereiteter `tenant_id`; eigene Verfügbarkeiten per REST- oder Datei-Import; Webhooks und Event-Feed.
- **D-15:** Referenzbetrieb auf einer VM hinter einem vorhandenen nginx mit Let's Encrypt. Der Proxy-Container bleibt optional.
- **D-16:** Sicherheitslücken werden über GitHub Private Vulnerability Reporting gemeldet, ersatzweise an `abuse@rsm-freilassing.de`. Lizenzvermerk „open-spri contributors“.
- **Sprache:** Doku, Commits und UI auf Deutsch, Quellcode offiziell auf Englisch.

## Konsequenzen

- Der KFT-Umfang umfasst alle Testfälle beider Blätter: 108 als Auftraggeber, 103 als Leistungserbringer.
- Die Software braucht für Diagnose eine Anbindung an Netz- und Monitoringsysteme des Betreibers. Dafür gibt es die SPI `DiagnosticsProvider` mit den Implementierungen Webhook und manuell.
- Diagnose muss rund um die Uhr verfügbar sein (7×24), wie EST.
- Das Let's-Encrypt-Zertifikat muss ECDSA P-256 oder RSA ≥ 3072 verwenden. Ein Selbsttest prüft das.
