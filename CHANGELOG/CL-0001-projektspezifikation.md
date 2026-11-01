# CL-0001: Projektspezifikation und Grundsätze

| | |
|---|---|
| Datum | 2026-11-01 |
| Meilenstein | vor M0 (Spezifikationsphase) |
| Bezug | docs/AUFGABE-SPRI.md v0.2 |

## Motivation

Vor der Entwicklung brauchen wir einen abgestimmten, nachvollziehbaren Stand zu Umfang, Architektur und Zertifizierungsweg von open-spri.

## Änderungen

- `docs/AUFGABE-SPRI.md`: Aufgabenbeschreibung bis zur Entwicklungsfreigabe. Sie enthält Ziel, Zertifizierungsstrategie, Scope-Entscheidungen D-01 bis D-14, fachliche Regeln, Sicherheit, Architektur, REST-Überblick, Teststrategie, Meilensteine M0 bis M9 und offene Punkte.
- `CLAUDE.md`: Grundsätze und Arbeitsregeln
- `README.md`: Projektbeschreibung und geplanter Funktionsumfang
- `CHANGELOG/`: Konvention und Vorlage
- `CONTRIBUTING.md`, `LICENSE` (MIT), `.gitignore` (AK-Dokumente werden nicht veröffentlicht)
- `scripts/fetch-spec.sh`: lädt die AK-Dokumente
- `scripts/gen-testmatrix.py`: erzeugt die KFT-Testfallmatrix (Anhang A)
- `docs/adr/`: ADR-Konvention und ADR-0001 (Ausgangsentscheidungen)

## Auswirkungen

Noch kein Code, keine Auswirkungen auf den Betrieb.

## Tests

Keine. `scripts/gen-testmatrix.py` wurde gegen `Testfaelle_SPRIv4X_20250523.xlsx` ausgeführt: 108 TF in der Rolle Auftraggeber, 103 TF in der Rolle Leistungserbringer.
