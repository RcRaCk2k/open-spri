# Interpretationen und Abweichungen gegenüber der S/PRI-Spezifikation

Hier steht jede Stelle, an der open-spri die AK-Spezifikation auslegen muss, weil sie lückenhaft oder widersprüchlich ist oder weil wir bewusst davon abweichen. Jeder Eintrag nennt die Quelle. Grundsatz 1 in CLAUDE.md: keine Blackbox.

| Nr. | Quelle | Thema | Unsere Auslegung | Status |
|---|---|---|---|---|
| SA-001 | Meldungscodetabelle (Export 2026-11-01), Testfallliste 23.05.2025 | Die Codes 1089 (Z.6.1.06/Z.13.1.06) und 7501 (Z.15.2.01/Z.16.2.01) fehlen in der Tabelle | Wir führen sie als lokale Ergänzung mit Kennzeichnung | beim AK angefragt (OP-11) |
| SA-002 | Spezifikation 11.2.3.1, Meldungscodetabelle | TEQ-Code bei fehlerhafter WS-Security-Signatur ist nicht festgelegt | vorläufig `0999` (technischer Fehler). Laut Spezifikation geben wir nur die Fehlernummer zurück. | beim AK angefragt (OP-11) |
| SA-004 | Anlage 3 `policy.xml` vs. Security-Dokument V3.0, 2.5 | Die Policy nennt AlgorithmSuite „Basic256“, deren Standard-Signaturalgorithmus RSA-SHA1 ist. Das Security-Dokument verlangt SHA256withRSA. | Wir signieren mit SHA256withRSA (Digest SHA-256) und akzeptieren nur SHA-2. Das neuere Dokument hat Vorrang. | umgesetzt (M1) |
| SA-005 | Anlage 1, Kontrollattribut „Signatur-Id“ | Format von Issuer und Serial ist nicht festgelegt | Issuer als X.500-Name (RFC 2253, z. B. `CN=…,O=…`), Serial dezimal. Weichen sie vom tatsächlich signierenden Zertifikat ab, antworten wir mit TEQ 0999. | umgesetzt (M1), Bestätigung im KFT |
| SA-006 | XSD `AenderungskennzeichenType` vs. Spezifikation 4.1 | Die Tabelle in 4.1 nennt „Reklamation“ als Änderungskennzeichen bei EST, die XSD kennt nur Standard, Storno und Terminverschiebung. Die Reklamation ist in der XSD eine eigene Struktur (`EntstoerungsReklamationType`). | Wir folgen der XSD. `ChangeIndicator.REKLAMATION` wird in M2 auf die Reklamationsstruktur abgebildet. | offen (M2) |
| SA-007 | Security-Dokument 2.6 | Für Anfragen von nicht freigeschalteten IPs ist keine TEQ definiert | HTTP 403 ohne SOAP-Inhalt. Die Anfrage wird nicht archiviert, aber protokolliert. | umgesetzt (M1) |
| SA-003 | Replikat-Spezifikation 1.7, Kap. 2 | Für die Online-Verfügbarkeitsabfrage ist kein Format festgelegt | REST-Abfrage, deren Antwort den Feldern eines Replikat-Datensatzes entspricht (AUFGABE Kap. 5.4) | Vorschlag an den AK (F4) |
