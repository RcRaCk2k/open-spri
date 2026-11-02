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

### Teil 2: alle Geschäftsfälle, Bestandsaufbau
- `OrderTemplates`: Vorlagen für KUE-AG, AEN-LMAE, LAE, PV, EST (auch Reklamation), DIAG-GET und DIAG-SET. `OrderTemplatesTest` prüft jede gegen XSD und Anlage 1.
- `ScenarioRunner`:
  - **Bestandsaufbau (BA = X):** ein vorgeschalteter NEU-Auftrag mit Standardkette, dessen Vertragsnummer und LineID der Testfall verwendet.
  - **KUE-LE:** KDLE über `/terminations` bzw. vom Simulator.
  - **abgebender EKP:** AKM-PV vom Simulator, RUEM-PV über `/pv-requests/{id}/reply`, auch negativ.
  - **Reklamation** in beiden Rollen.
  - **Diagnose** mit Diagnoseergebnis.
- `MessageDefaults`: Störungsnummer in der QEB (EST), Entstörergebnis und Diagnoseergebnis in der ERLM, Zeitfenster in der TBK, Pflichtfelder von AKM-PV und ABM-PV.
- `TestCaseVariants`: Negativ-Testfälle (Storno statt Standard, Diagnose statt Änderung, Produkt „Dummy“, Termin mehr als 179 Tage) und der Providerwechsel als Leistungserbringer gelten als offen, bis sie umgesetzt sind. Ohne die Besonderheit würden sie mit einem Standardauftrag grün.
- **`MessageCodeCatalog`:** Eine lokale Erweiterung (SA-008) schränkte bisher einen Code, der für alle Geschäftsfälle gilt (leere Liste), auf die ergänzten Geschäftsfälle ein. Jetzt bleibt er uneingeschränkt.
- **Abgeleitete Codetabelle:** schränkt Codes nicht mehr auf Geschäftsfälle ein, weil die Testfallliste oft nur `*2` angibt. Gefunden mit dem frischen Klon ohne AK-Tabelle (wie CI); die Prüfung je Geschäftsfall läuft mit der AK-Tabelle.
- **Anlage 1 vs. XSD bei EST (SA-015):** Anlage 1 verlangt eine Auftragsposition, die `EntstoerungType` nicht hat. Ohne Korrektur hätte open-spri im Modus `enforce` jeden Entstörauftrag abgewiesen, auch die eigenen. Neue Bedingung `not GF EST` in `anlage1-conditions.txt`.

### Teil 3: besondere Auftragsinhalte
- `OrderTemplates`: Projektkenner, Auftragskenner (eindeutige Auftragsklammer), Besteller (Dritte), VorabstimmungsID (NEU, KUE-AG), HomeID (über `vormieter`), Konnektivitätsauftrag mit und ohne Eigentümerdaten.
- `ScenarioRunner.prepare()`: legt Projektvereinbarung und Vollmacht über die REST-API an. So prüfen die Szenarien auch R-11 und R-12 aus M4.
- `OutboundPayloads`: Meldungen übernehmen `auftraggebernummerBesteller` und `vorabstimmungId` aus dem Auftrag, wie schon Vertragsnummer und Auftraggebernummer.

### Teil 4: Bestand, Providerwechsel als Leistungserbringer
- **Bestand** (AUFGABE 5.6, 8.4 `inventory_contract`):
  - `InventoryBook` führt ihn bei jedem abgeschlossenen Auftrag nach: ENTM zu NEU/PV legt einen Vertrag an und beendet den bisherigen auf der Leitung, KUE-AG/KUE-LE beendet ihn, ENTM-PV als abgebender EKP beendet unseren. Ein Storno ändert nichts.
  - REST `GET /inventory`, `GET /inventory/{lineId}`.
- **Providerwechsel als Leistungserbringer (5.5.1):** Der abgebende EKP ist der Inhaber des aktiven Vertrags auf der LineID. `OutboundOrderService` hält ihn mit der AKM-PV am Auftrag fest (`OrderData.handover`).
  - AKM-PV, ABM-PV, ERLM-PV, ENTM-PV und ABBM-PV gehen an ihn, mit seiner Vertrags- und Auftraggebernummer (Anlage 1) und einem eigenen Reihenfolgeschlüssel.
  - Ohne Bestandsvertrag antwortet die API mit 409. **Bisher gingen diese Meldungen fälschlich an den Auftraggeber.**
- Migration V12: `inventory_contract`; `spri_order.handover_partner_id`, `handover_contract_number`, `handover_client_number`.

### Teil 5: Negativ-Testfälle als Leistungserbringer
- **Geschäftsfallart:** `OrderRules` prüft, ob die Geschäftsfallart (`geschaeftsfallArt`) zum Geschäftsfall passt. `BusinessCase.kind` ist die Zuordnung aus SA-011, die jetzt auch `OutboundPayloads` verwendet.
- **Storno oder Terminverschiebung zu einem unbekannten Auftrag** legt jetzt einen Auftrag in der Phase `NOT_STARTED` mit offener Änderung an (`OrderLifecycle.requestWithoutOrder`). Bisher entstand nur ein Verlaufseintrag ohne Auftrag, und der Leistungserbringer konnte nicht antworten. Der Befund lautet „unzulässiges Änderungskennzeichen“. Zulässig ist nur eine ABBM, die den Auftrag beendet (KFT Z.11.2.01, ABBM 1007).
- Szenarien: Der Simulator sendet die fehlerhaften Aufträge. Der Runner verlangt den passenden Befund und legt dafür unseren Produktkatalog an.

### Teil 6: Negativ-Testfälle als Auftraggeber (ADR-0008)
- **Übersteuerung:** `RuleOverride` mit `R-05`, `GESCHAEFTSFALLART` und `AENDERUNGSKENNZEICHEN`, über `?override=` an `POST /orders?send=true` und `POST /orders/{id}/send`.
  - Neuer Scope `orders:override`. Er gehört zu keiner Rolle; ein Admin kann ihn ausdrücklich in ein Token aufnehmen (`Role.grantable`).
  - Die Übersteuerung steht im Audit-Log und als Befund „Übersteuert (ADR-0008)“ im Verlauf.
  - Ein neuer Auftrag mit „Storno“ erhält den Zustand `requestWithoutOrder`; auf ihn ist nur eine ABBM zulässig.
- Szenarien: Z.2.2.01, Z.2.2.02, Z.4.2.01 und Z.5.2.01 laufen mit Übersteuerung. **Alle 211 Testfälle sind grün, das Coverage-Gate steht auf 0.**
- ADR-0008, CLAUDE.md („Was nicht erlaubt ist“), `docs/api/auth.md`, `docs/api/auftraege.md`.

## Auswirkungen
- S/PRI-Schnittstelle: Meldungen zu Aufträgen im Namen Dritter tragen jetzt die Auftraggebernummer des Bestellers, Meldungen zu Aufträgen mit VorabstimmungsID die VorabstimmungsID. TBK-Meldungen werden jetzt XSD-konform gebaut; die ENTM als Leistungserbringer enthält die Vertragsnummer aus der ABM. Entstöraufträge ohne Auftragsposition sind jetzt auch nach Anlage 1 gültig (SA-015), eingehend wie ausgehend.
- S/PRI-Schnittstelle (Teil 4): Meldungen an den abgebenden EKP gehen jetzt an ihn statt an den Auftraggeber.
- REST-API: `GET /inventory`, `GET /inventory/{lineId}` (Teil 4)
- Datenbank: Migration V12 (Teil 4)
- Fachlogik (Teil 5): neuer Befund „Unzulässiger Geschäftsfall“, eigene Aufträge mit unpassender Geschäftsfallart werden abgewiesen (409); Änderungen zu unbekannten Aufträgen erzeugen einen Auftrag, auf den nur ABBM zulässig ist.
- Sicherheit (Teil 6): neuer Scope `orders:override`, in keiner Rolle (ADR-0008)
- REST-API (Teil 6): Parameter `override` an `POST /orders` (mit `send=true`) und `POST /orders/{id}/send`
- Konfiguration: keine Änderung
- Build: neues Modul `simulator`; `:app`-Tests laden die Testfallliste über `:simulator:downloadKftTestCases`

## Tests
- Teil 6: `KftScenarioTest` **211 von 211 grün**, Gate 0; `OutboundOrderServiceTest` (Übersteuerung einzeln wirksam, Befund im Verlauf, Storno als neuer Auftrag); `OrderApiTest` (409 ohne, 403 ohne Scope, 400 bei unbekanntem Wert und beim Entwurf, 202 mit Scope, Befund im Verlauf); `SecurityTest` (Scope in keiner Rolle, nur ausdrücklich im Admin-Token)
- Teil 5: `KftScenarioTest` 207 Testfälle grün, 4 offen (Z.2.2.01, Z.2.2.02, Z.4.2.01, Z.5.2.01: eigene Prüfungen verhindern den fehlerhaften Auftrag); `InboundOrderServiceTest` (Storno zu unbekanntem Auftrag, Geschäftsfallart)
- Teil 4: `KftScenarioTest` 197 Testfälle grün, 14 offen (Negativ-Testfälle); `InventoryBookTest` (Vertrag aus NEU/PV, Ablösung, Kündigung, Storno ohne Wirkung, abgebender EKP, Providerwechsel an den Vertragsinhaber, 409 ohne Bestand); `ApplicationIntegrationTest` (Bestand und abgebender EKP auf MariaDB)
- Teil 3: `KftScenarioTest` 191 Testfälle grün, 20 offen (Negativ-Testfälle, Providerwechsel als Leistungserbringer); `OrderTemplatesTest`: NEU mit allen Varianten
- Teil 2: `KftScenarioTest` 151 Testfälle grün, 60 offen mit Grund; `OrderTemplatesTest` (jede Vorlage gültig nach XSD und Anlage 1, richtiger Geschäftsfall, Reklamation)
- `KftScenarioTest`: 56 Testfälle grün (NEU in beiden Rollen ohne besondere Auftragsinhalte, dazu S.1.1.02/S.8.1.02 neuer Termin, S.1.2.6/S.8.2.6 zwei Codes, S.1.2.5/S.8.2.5 Dublette mit 1010, S.8.2.10 TEQ 0991 mit automatischer Wiederholung), 155 offen mit Grund
- `KftSequenceParserTest`, `KftSequenceReplayTest`: aus `:domain` nach `:simulator` verschoben, unverändert grün
