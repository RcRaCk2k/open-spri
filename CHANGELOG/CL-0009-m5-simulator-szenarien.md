# CL-0009: M5 – Simulator und KFT-Szenarien

| | |
|---|---|
| Datum | 2026-11-01 |
| Meilenstein | M5 |
| Bezug | AUFGABE 3.3, 10.1 Punkt 5 und 6, 10.3; Grundsatz 11; KFT-Testfallliste 23.05.2025 |

## Motivation
Der KFT bei Enghouse bricht nach drei erfolglosen Versuchen ab. Deshalb müssen alle 211 Testfälle beider Rollen vorher gegen einen eigenen Simulator grün sein. Jeder Testfall wird ein automatisierter Test mit seiner TF-ID im Namen, ein Coverage-Gate verhindert Lücken.

## Änderungen

### Teil 1: Simulator, Szenarien, NEU
- Neues Modul **`simulator`** (AUFGABE 8.2):
  - `KftTestCaseList`, `KftSequenceParser`: aus den Domain-Tests übernommen. Der Parser kennzeichnet jetzt Schritte mit dem anderen Provider (`<…>`). Die Testfallliste wird über `openspri.kft.testCases` bzw. `OPENSPRI_KFT_TEST_CASES` gelesen; der Download liegt jetzt in `:simulator`.
  - `ScenarioPlanner`: Testfall → Schritte mit Seite (unsere Instanz oder Simulator), abgeleitet aus dem Absender des Meldungstyps.
  - `SimulatedPartner`: Gegenstelle mit eigenem Schlüssel und eigenem HTTP-Endpunkt. Eingang über `InboundProcessor` mit Anlage 1 im Modus `enforce`, Ausgang über `OutboundPayloads` und `SpriClient`. Auf Wunsch einmalig TEQ 0991.
  - `OpenSpriClient`: steuert unsere Instanz nur über `/api/v1`.
  - `ScenarioRunner`: führt die Schritte aus und prüft TEQ, Befunde, Meldungstyp, Änderungskennzeichen und Codes. Am Ende muss der Auftrag abgeschlossen sein; bei einem Fehler gibt er das Protokoll aus.
  - `OrderTemplates` (NEU), `MessageDefaults` (Attribute je Meldungstyp; optionale werden wie beim KFT-Simulator mitgefüllt), `TestCaseCodes` (aus der Testfallliste abgeleitete Meldungscodetabelle für die CI), `TestCaseVariants` (Besonderheiten je Testfall).
- **`KftScenarioTest`** (`:app`): ein dynamischer Test je TF-ID und der Test `coverage gate` als Ratsche. Teil 1 erlaubt 155 offene Testfälle.
- **Korrekturen, die die Szenarien gefunden haben:**
  - `OutboundPayloads`: TBK-LE und TBK-AG verwenden den XSD-Typ `MeldungsattributeTBKType`. Bisher wurde ein nicht vorhandener Typ `…TBK-LEType` gesucht.
  - `OutboundOrderService.sendMessage`: Als Leistungserbringer übernehmen wir die Vertragsnummer unserer ersten ABM in den Auftrag, wie bisher schon die LineID. Ohne sie fehlte die Vertragsnummer in der ENTM, obwohl die XSD sie verlangt.
  - Test-Fixtures sind jetzt threadsicher: `InMemoryOutbox` sperrt je Reihenfolgeschlüssel wie `SKIP LOCKED` in der Datenbank, Repository und Ereignisse verwenden `CopyOnWriteArrayList`. Bisher sendeten vier parallele Worker dieselbe Nachricht viermal. Die Datenbank-Outbox war davon nicht betroffen.
  - `InMemoryPartners` dient wie `JdbcPartnerDirectory` auch als `PartnerDirectory`.
- Doku: `docs/kft-szenarien.md` (Ablauf, Ausführen, Auslegung der Testfallliste, Gate, Stand), SA-014.

## Auswirkungen
- S/PRI-Schnittstelle: TBK-Meldungen werden jetzt XSD-konform gebaut; die ENTM als Leistungserbringer enthält die Vertragsnummer aus der ABM.
- REST-API, Datenbank, Konfiguration: keine Änderung
- Build: neues Modul `simulator`; `:app`-Tests laden die Testfallliste über `:simulator:downloadKftTestCases`

## Tests
- `KftScenarioTest`: 56 Testfälle grün (NEU in beiden Rollen ohne besondere Auftragsinhalte, dazu S.1.1.02/S.8.1.02 neuer Termin, S.1.2.6/S.8.2.6 zwei Codes, S.1.2.5/S.8.2.5 Dublette mit 1010, S.8.2.10 TEQ 0991 mit automatischer Wiederholung), 155 offen mit Grund
- `KftSequenceParserTest`, `KftSequenceReplayTest`: aus `:domain` nach `:simulator` verschoben, unverändert grün
