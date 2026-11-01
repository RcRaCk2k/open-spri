# ADR-0001: Ausgangsentscheidungen der Spezifikationsphase

Status: angenommen (D-10 bis D-14 vorgeschlagen) | Datum: 2026-11-01

## Kontext

open-spri soll S/PRI V4.4 in beiden Rollen implementieren, als Open Source veröffentlicht und vom AK S&P zertifiziert werden. Die Entscheidungen fielen im Abstimmungsgespräch zur Aufgabenbeschreibung.

## Entscheidung

Es gilt das Entscheidungslog D-01 bis D-14 in [`docs/AUFGABE-SPRI.md`, Kap. 4](../AUFGABE-SPRI.md#4-scope-entscheidungen-entscheidungslog). Kurzfassung:

- **Rollen:** beide Rollen, Auftraggeber (inkl. abgebender EKP) und Leistungserbringer. Produktgruppen FTTH, FTTB, FTTC.
- **Geschäftsfälle:** NEU, KUE-AG, KUE-LE, AEN-LMAE, LAE, PV, EST. Diagnose erst in v2.
- **Funktionen:** Konnektivitätsauftrag, HomeID, Bestellung im Namen Dritter
- **Verfügbarkeit:** Replikat-Export und -Import, Online-Abfrage
- **Providerwechsel:** WBCI-Modus im Setup wählbar (eigene Schnittstelle oder Dienstleister)
- **API:** API-first. Das Frontend nutzt ausschließlich die öffentliche REST-API. Die Authentifizierung ist modular aufgebaut (v1: lokale Benutzer und Long-Life-Token).
- **Technik:** Kotlin, Spring Boot 3, Apache CXF, React + TypeScript, MariaDB, Docker Compose
- **Umsetzung:** Claude Opus übernimmt alle Arbeitspakete
- **Sprache:** Doku und Commits deutsch, Code englisch

## Konsequenzen

- Die Zertifizierung umfasst rund 80 Pflicht-Testfälle je Rolle (siehe Anhang A).
- Die Fachlogik ist protokollneutral. Damit bleibt ein späterer FIT-Adapter möglich.
- Offene Punkte OP-01 bis OP-12 (Kap. 15) müssen vor den jeweiligen Meilensteinen geklärt sein.
