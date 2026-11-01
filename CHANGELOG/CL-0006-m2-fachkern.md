# CL-0006: M2 – Fachkern

| | |
|---|---|
| Datum | 2026-11-01 |
| Meilenstein | M2 |
| Bezug | AUFGABE Kap. 5.2 (R-01 bis R-15), 5.3, 11 (M2); Spezifikation 4, 5, 6, 7.2, 10.4; Testfallliste 23.05.2025 |

## Motivation

M1 nimmt Nachrichten technisch an. M2 gibt ihnen fachliche Bedeutung: Jeder Geschäftsfall hat einen Zustandsautomaten, Meldungscodes werden geprüft, Aufträge werden auf Attributebene gegen Anlage 1 validiert und eingehende Nachrichten verarbeitet.

## Änderungen

### Teil 1: Meldungscodes
- `MessageType` (alle Meldungstypen außer TEQ, mit Absender und Empfänger), `MessageCode`, `MessageCodeCatalog` (R-13: Code vorhanden, für Meldungstyp und Geschäftsfall vorgesehen; REK gilt als EST mit Reklamation)
- `MessageCodeTableParser`: AK-Export (UTF-16, Tab, mehrzeilige Zellen, HYPERLINK-Formel) und neutrales CSV. Versioniert über SHA-256.
- Lokale Ergänzungen mit Quellenangabe: Codes 1089 und 7501 (SA-001), 6050 für MTAM (SA-008)
- `BusinessCase.wireCode`: In Meldungen heißt Diagnose `GET`/`SET`.

### Teil 2: Zustandsautomaten
- `OrderLifecycle`, ein Automat für beide Rollen:
  - Phasen SUBMITTED → ACKNOWLEDGED (QEB) → CONFIRMED (ABM) → APPOINTMENT_REQUESTED (TAM/MTAM) → COMPLETED (ERLM) → CLOSED (ENTM), dazu ABORTED (ABBM)
  - Anfragen Storno, Terminverschiebung und Reklamation, deren Antworten über das Änderungskennzeichen zugeordnet werden
  - Austausch mit dem abgebenden EKP (AKM-PV … ENTM-PV/ABBM-PV)
  - KUE-LE (Start mit KDLE) und Diagnose
- Regeln: zulässige Meldungen je Geschäftsfall (7.2), R-03 (je ein offener Storno und eine offene Terminverschiebung), R-04 (Storno beendet ohne eigene Erledigung), R-06 (wiederholte ABM), R-08 (TAM/MTAM), R-09 (ERLM-K), Terminverschiebung bei Konnektivität und Klammer erst nach ABM, Reklamation erst nach Abschluss
- `allowedEvents` für die „erlaubten Aktionen“ der API

### Teil 3: Attributprüfung nach Anlage 1 (ADR-0006)
- Neues Modul `spri-rules`:
  - Generator (nur Build-Zeit): Anlage 1 mit Prüfsumme laden, alle **268 Zeilen** über die JAXB-Klassen auf XML-Pfade abbilden, `anlage1-rules.tsv` erzeugen
  - `anlage1-mapping.txt`: 17 begründete Zuordnungen bei Namensabweichungen, choice-Gruppen und dem XSD-Tippfehler `dignoseergebnis`
  - `anlage1-conditions.txt`: 8 Bedingungen aus der Spalte „Anmerkung“ mit Zeilenzitat
- `AttributeValidator`: Spalte aus Geschäftsfall (xsi:type) und Produktgruppe (Produktbezeichner, KOAX/HFC → FTTH, Konnektivität) bzw. Meldungstyp. Alternativgruppen, Bedingungen und Meldung mit Anlage-1-Zeile.
- Prüfkette Schritt 4b. Client prüft vor dem Senden (`Outcome.NotSent`). Modi eingehend `report` und ausgehend `enforce`, einstellbar über `openspri.soap.attribute-rules.inbound|outbound`.
- Archiv: neue Spalte `findings` (Migration V3) für Verstöße im Modus report

### Teil 4: Fachliche Verarbeitung
- Fachmodell `Order`, `OrderData`, `OrderRole` (AG, LE, abgebender EKP), `OrderHistoryEntry`, Port `OrderRepository`
- `OrderRules`:
  - R-02 (TV mit anderem Termin), R-05 (< 180 Tage), R-11 (Besteller formal), R-12 (Kenner, Klammer), R-15 (LineID-Bildungsregel)
  - Schlüssel für R-10 (LineID, sonst HomeID, sonst normalisierte Adresse)
- `InboundOrderService`:
  - Aufträge der Partner (wir sind LE): neu anlegen, Dublette nach 11.4, R-10, Storno/TV/Reklamation über den Automaten
  - Meldungen (wir sind AG, LE für AG-Meldungen oder EKPab): Zuordnung zum Auftrag, KDLE und AKM-PV legen den Auftrag an, Prüfung der Meldungscodes (R-13), Übernahme von LineID und Vertragsnummer
  - Tolerant: unpassende Meldungen werden mit Befund vermerkt, der Zustand bleibt.
- `SpriPayloadMapper` (XML → Fachmodell), `InboxWorker` (zeitgesteuert, Reihenfolge des Eingangs)
- Meldungscodetabelle: Import beim Start aus `OPENSPRI_MESSAGE_CODES_FILE`, versioniert über die Prüfsumme in `message_code_table`, aktive Version im Speicher (`MessageCodeCatalogHolder`)
- Migration V4: `spri_order`, `spri_order_history`, `message_code_table`, Verarbeitungsstatus in `inbox_message`
- Compose: `./config` → `/etc/openspri` (Meldungscodetabelle). Doku in `docs/betrieb.md`.
- **Neue Einführung** `docs/grundlagen/` (S/PRI für Entwickler mit Ablauf-, Fluss- und Zustandsdiagramm, Regel-Code-Zuordnung) und Glossar

## Auswirkungen

- S/PRI: Eingehende Aufträge und Meldungen werden jetzt fachlich verarbeitet. Die TEQ ist unverändert.
- REST-API: unverändert (die Endpunkte für Aufträge folgen mit M4)
- Datenbank: Migrationen V3 und V4
- Konfiguration: `OPENSPRI_MESSAGE_CODES_FILE`, `OPENSPRI_SOAP_ATTRIBUTE_RULES_INBOUND`, `openspri.inbox.poll-interval`
- Einschränkung bis M3: Die Eingangsverarbeitung läuft auf **einer** Instanz. Antworten des LE (QEB/ABBM aufgrund der Befunde) und das Senden über die Outbox folgen mit M3/M4.

## Tests

- `KftSequenceReplayTest`: Die Testfallliste wird mit Prüfsumme geladen, alle **211 Meldungsfolgen** beider Blätter werden geparst und durch den Automaten gespielt. Jede Meldung muss akzeptiert werden, jeder Auftrag muss abgeschlossen enden. Außerdem werden alle Codes gegen die Meldungscodetabelle geprüft (lokal, solange die Tabelle nicht im Repository liegen darf).
- `AttributeValidatorTest` (8): alle 268 Regeln geladen, Beispielnachrichten konform, Pflichtfeld fehlt, Termin Neu nur bei Terminverschiebung, fremdes GF-Attribut in Meldung, Firma statt Person
- `InboundProcessorTest` (+3): enforce → 0995, report → angenommen mit Befund, konformer Auftrag
- `SoapEndToEndTest` (+1): eigener Auftrag mit Anlage-1-Verstoß wird nicht gesendet
- `InboundOrderServiceTest` (10): Anlage, Dublette, R-05, R-10, TV/R-02, Meldungen mit Codeprüfung, Meldung außer der Reihe, AKM-PV/KDLE, unbekannter Auftrag, LineID
- `SpriPayloadMapperTest` (3), `SoapEndToEndTest` (+1: SOAP → Eingang → Auftrag, Dublette)
- `ApplicationIntegrationTest` (CI, MariaDB): Roundtrip bis `spri_order` und `spri_order_history`
- `OrderLifecycleTest` (12 Negativ- und Regelfälle), `KftSequenceParserTest` (Notation), `MessageCodeCatalogTest` (5, inkl. Import der echten AK-Tabelle)
