# CHANGELOG

Größere Änderungen bekommen hier eine eigene Datei `CL-NNNN-kurztitel.md`. Die Nummer ist fortlaufend und wird nie wiederverwendet. Der zugehörige Commit nennt sie im Betreff, z. B. `Outbox mit Reihenfolgegarantie [CL-0007]`.

**Größer** heißt: Die Änderung betrifft Verhalten an der S/PRI- oder REST-Schnittstelle, das Datenmodell, die Konfiguration, die Sicherheit, eine Grundsatzentscheidung oder einen Meilenstein.

## Vorlage

```markdown
# CL-NNNN: Titel

| | |
|---|---|
| Datum | JJJJ-MM-TT |
| Meilenstein | M… |
| Bezug | Kapitel in docs/AUFGABE-SPRI.md, TF-IDs, ADR, Issue |

## Motivation
Warum ist die Änderung nötig?

## Änderungen
Was wurde geändert?

## Auswirkungen
- S/PRI-Schnittstelle:
- REST-API:
- Datenbank (Migration):
- Konfiguration:
- Betrieb/Sicherheit:

## Tests
Welche Tests belegen die Änderung?
```

## Einträge

| Nr. | Titel | Datum |
|---|---|---|
| [CL-0001](CL-0001-projektspezifikation.md) | Projektspezifikation und Grundsätze | 2026-11-01 |
