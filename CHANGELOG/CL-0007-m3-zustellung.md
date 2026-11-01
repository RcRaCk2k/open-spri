# CL-0007: M3 – Postausgang, Wiederholungen, Ausfall-Benachrichtigung, Eingang für mehrere Instanzen

| | |
|---|---|
| Datum | 2026-11-01 |
| Meilenstein | M3 |
| Bezug | AUFGABE Kap. 8.3, NF-03 bis NF-06; Spezifikation 11.2.3.1–11.2.3.3; KFT S.1.2.10; ADR-0007 |

## Motivation

M2 nimmt Nachrichten an und verarbeitet sie. M3 sorgt dafür, dass unsere eigenen Aufträge und Meldungen zuverlässig, in der richtigen Reihenfolge und mit den Wiederholungsregeln der Spezifikation beim Partner ankommen. Außerdem können mehrere Instanzen parallel laufen.

## Änderungen

- **domain/delivery:**
  - `OutboxEntry`, `DeliveryStatus`, `AttemptResult`, `RetryPolicy`
  - `DeliveryDecisions` (Entscheidungstabelle nach 11.2.3.1/11.2.3.2)
  - `DeliveryService` (Versand, Ausfallverfolgung, Benachrichtigung)
  - Ports `Outbox`, `OutageRegistry`, `Notifier`
- **domain/order:**
  - `OutboundOrderService`: eigene Aufträge, Änderungsaufträge und Meldungen. Streng geprüft gegen Zustandsautomat, Inhaltsregeln, Meldungscodetabelle und Absenderrolle, in einer Transaktion mit dem Postausgang. Port `Transactions`.
  - Reihenfolgeschlüssel je Auftrag (`Order.sequenceKey`), beim ersten Versand festgelegt
- **domain/partner:** `endpointUrl`, `technicalContactEmail`, `PartnerDirectory.findById`
- **persistence:**
  - `JdbcOutbox` und `JdbcInboxQueue` mit `FOR UPDATE SKIP LOCKED`: je Schlüssel genau eine Nachricht in Arbeit, über alle Instanzen. Ein FAILED-Eintrag im Eingang blockiert seinen Auftrag.
  - `JdbcOutageRegistry` (atomare Benachrichtigung), `SpringTransactions`
  - Migration V5
- **spri-soap:**
  - `SpriClient` unterscheidet „sicher nicht angekommen“ und „Ankunft unklar“.
  - `OutboxSender`, `DeliveryConfiguration` (Einstellungen `openspri.outbox.*`, parallele Sender auf virtuellen Threads), `MailNotifier` (Spring Mail, ohne Mailserver nur Log)
- **Korrektur:** Eine konfigurierte Meldungscodetabelle, die nicht existiert, wird jetzt als Fehler gemeldet, mit Hinweis auf den Pfad im Container. Vorher fiel open-spri still auf die alte Version zurück.
- **Test-Fixtures:** In-Memory-Ports im Domain-Modul (`java-test-fixtures`), gemeinsam für alle Module. Kleine eigene Test-Meldungscodetabelle.
- **Betrieb:** Compose reicht Mail- und Postausgangseinstellungen durch. `docs/betrieb.md` beschreibt Postausgang, Status und Benachrichtigungen. ADR-0007.

## Auswirkungen

- S/PRI: Eigene Nachrichten werden über den Postausgang versendet. Der Endpunkt des Partners kommt aus `partner.endpoint_url`.
- REST-API: unverändert (Aufträge, Meldungen und Zustellmonitor folgen mit M4)
- Datenbank: Migration V5 (`outbox_message`, `partner_outage`, Spalten an `partner`, `spri_order`, `inbox_message`)
- Konfiguration: `OPENSPRI_OUTBOX_*`, `SPRING_MAIL_*`, `OPENSPRI_NOTIFICATIONS_*`
- Ohne importierte Meldungscodetabelle sind keine eigenen Meldungen möglich (R-13).

## Tests

- `DeliveryDecisionsTest` (7): jede Zeile der Entscheidungstabelle
- `DeliveryServiceTest` (3): Reihenfolge je Schlüssel bei unabhängigen Schlüsseln, Blockade und Freigabe durch E-Mail-Kanal, Ausfall-Mail einmal täglich mit Neustart nach Erfolg
- `OutboundOrderServiceTest` (5): Auftrag und Postausgang gemeinsam, Regelverstoß wird nicht gesendet, strenge Reihenfolge, falscher Code/fehlender Code/falsche Rolle, stabiler Schlüssel trotz neuer LineID
- `SoapEndToEndTest` (+2): Antwort als LE mit QEB über den Postausgang an den eigenen Endpunkt; nicht erreichbarer Partner führt zur Wiederholung
- `ConcurrencyIntegrationTest` (CI, MariaDB): je 6 parallele Worker auf Postausgang und Eingang mit 18 Einträgen in 3 Schlüsseln. Jeder Eintrag genau einmal, je Schlüssel in Reihenfolge, nie zwei gleichzeitig.
