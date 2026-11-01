# Mitarbeit an open-spri

Danke für dein Interesse! open-spri soll für den gesamten Markt nachvollziehbar und nutzbar sein. Deshalb gelten ein paar feste Regeln.

## Bevor du anfängst

1. Lies [`CLAUDE.md`](CLAUDE.md). Dort stehen die Grundsätze, an denen sich jede Änderung messen lassen muss.
2. Lies die betroffenen Kapitel in [`docs/AUFGABE-SPRI.md`](docs/AUFGABE-SPRI.md).
3. Lade die Spezifikation: `./scripts/fetch-spec.sh`. Die Dokumente sind urheberrechtlich geschützt und dürfen **nicht** committet werden.
4. Für größere Vorhaben gilt: zuerst ein Issue eröffnen und den Ansatz abstimmen.

## Änderungen einreichen

- Ein Pull Request enthält ein Thema, mit Tests und aktualisierter Doku.
- Commits sind kurz und bündig, auf Deutsch, im Imperativ, Betreff ≤ 72 Zeichen.
- Größere Änderungen bekommen einen Eintrag `CHANGELOG/CL-NNNN-*.md`, der im Commit referenziert wird (siehe [`CHANGELOG/README.md`](CHANGELOG/README.md)).
- Grundsatzentscheidungen brauchen ein ADR in `docs/adr/`.
- Verhalten an der S/PRI-Schnittstelle muss mit Quelle (Spezifikationskapitel bzw. TF-ID) belegt sein. Interpretationen gehören in `docs/spec-abweichungen.md`.

## Sprache

Doku, Commits, Issues und UI sind auf Deutsch. Code-Bezeichner und Kommentare sind auf Englisch. S/PRI-Fachbegriffe bleiben wie im Standard.

## Sicherheitslücken

Bitte **nicht** als öffentliches Issue melden. Der Meldeweg wird in `SECURITY.md` beschrieben (folgt mit M0).
