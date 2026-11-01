# CL-0002: Scope final, Diagnose in v1, Security-Meldeweg

| | |
|---|---|
| Datum | 2026-11-01 |
| Meilenstein | vor M0 (Spezifikationsphase) |
| Bezug | docs/AUFGABE-SPRI.md v0.3, ADR-0002 |

## Motivation

Der Auftraggeber hat die offenen Scope-Vorschläge bestätigt. Diagnose kommt in v1, weil ein späteres Nachrüsten einen weiteren, kostenpflichtigen KFT erfordern würde.

## Änderungen

- **AUFGABE-SPRI.md:**
  - D-03 um DIAG erweitert, D-10 bis D-14 bestätigt
  - neu: D-15 (Betrieb hinter vorhandenem nginx mit Let's Encrypt) und D-16 (Security-Meldeweg, Lizenzvermerk)
  - neues Kapitel 5.11 Diagnose, REST-Endpunkte `/diagnostics`
  - Kapitel 7.4 mit nginx-Vorgaben und Prüfung des Zertifikatsschlüssels
  - OP-06 und OP-12 erledigt, OP-09 präzisiert, Freigabe-Checkliste aktualisiert
- **scripts/gen-testmatrix.py:** Diagnose-Testfälle sind jetzt Pflicht. Summen: 108 TF als Auftraggeber, 103 TF als Leistungserbringer.
- Neu: `SECURITY.md`, `CODE_OF_CONDUCT.md`, ADR-0002
- README, CONTRIBUTING und CLAUDE.md angepasst

## Auswirkungen

Noch kein Code. Der KFT-Umfang wächst um 4 Testfälle je Rolle.

## Tests

`scripts/gen-testmatrix.py` neu ausgeführt, Summen geprüft.
