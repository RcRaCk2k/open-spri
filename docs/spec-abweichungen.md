# Interpretationen und Abweichungen gegenüber der S/PRI-Spezifikation

Hier steht jede Stelle, an der open-spri die AK-Spezifikation auslegen muss, weil sie lückenhaft oder widersprüchlich ist oder weil wir bewusst davon abweichen. Jeder Eintrag nennt die Quelle. Grundsatz 1 in CLAUDE.md: keine Blackbox.

| Nr. | Quelle | Thema | Unsere Auslegung | Status |
|---|---|---|---|---|
| SA-001 | Meldungscodetabelle (Export 2026-11-01), Testfallliste 23.05.2025 | Die Codes 1089 (Z.6.1.06/Z.13.1.06) und 7501 (Z.15.2.01/Z.16.2.01) fehlen in der Tabelle | Wir führen sie als lokale Ergänzung mit Kennzeichnung | beim AK angefragt (OP-11) |
| SA-002 | Spezifikation 11.2.3.1, Meldungscodetabelle | TEQ-Code bei fehlerhafter WS-Security-Signatur ist nicht festgelegt | vorläufig `0999` (technischer Fehler). Laut Spezifikation geben wir nur die Fehlernummer zurück. | beim AK angefragt (OP-11) |
| SA-003 | Replikat-Spezifikation 1.7, Kap. 2 | Für die Online-Verfügbarkeitsabfrage ist kein Format festgelegt | REST-Abfrage, deren Antwort den Feldern eines Replikat-Datensatzes entspricht (AUFGABE Kap. 5.4) | Vorschlag an den AK (F4) |
