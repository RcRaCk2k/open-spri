# ADR-0007: Postausgang und Eingang mit Reihenfolgegarantie über die Datenbank

Status: angenommen | Datum: 2026-11-01 | Bezug: AUFGABE Kap. 8.3, NF-03 bis NF-06; Spezifikation 11.2.3

## Kontext

Die Spezifikation verlangt (11.2.3.3): Die Reihenfolge der Meldungen „muss in jedem Fall gewährleistet werden“, auch über Auftragskontexte hinweg, wenn sie denselben Sachverhalt betreffen. Zugleich sollen Nachrichten wiederholt werden, wenn die Gegenstelle nicht antwortet. Ein Auftrag ohne TEQ darf nicht automatisch mit derselben Nummer neu gesendet werden (11.2.3.1). open-spri soll ohne zusätzliche Infrastruktur auskommen, also ohne Message-Broker, und auch mit mehreren Instanzen funktionieren.

## Entscheidung

- **Transaktionaler Postausgang** (`outbox_message`): Zustandsänderung des Auftrags und Postausgangseintrag werden in einer Transaktion geschrieben (`OutboundOrderService`). Fachlogik ruft nie direkt SOAP auf.
- **Reihenfolgeschlüssel** je Auftrag:
  - Partner + LineID, wenn die LineID beim ersten Versand bekannt ist (z. B. Kündigung, Änderung). Dann sind auch verschiedene Aufträge zur selben Leitung geordnet.
  - Sonst Partner + externe Auftragsnummer
  - Der Schlüssel wird mit dem ersten Versand festgelegt und ändert sich nie. Eine später per ABM vergebene LineID ändert ihn also nicht.
- **Versand in einer Datenbanktransaktion mit Zeilensperre:** `SELECT … FOR UPDATE SKIP LOCKED` wählt den ältesten fälligen Eintrag, vor dem kein blockierender Eintrag mit demselben Schlüssel liegt. Die Sperre bleibt während des Versands bestehen, andere Instanzen überspringen die Zeile und sehen jüngere Einträge desselben Schlüssels als blockiert. Je Schlüssel ist so genau eine Nachricht unterwegs, über alle Instanzen hinweg. Der Eingang (`inbox_message`) arbeitet nach demselben Muster.
- **Status und Entscheidungen** (`DeliveryDecisions`, reine Funktion mit Tests):

  | Ergebnis | Meldung | Auftrag |
  |---|---|---|
  | TEQ OK | DELIVERED | DELIVERED |
  | TEQ 0991 (überlastet) | Wiederholung nach kurzer Wartezeit (KFT S.1.2.10) | ebenso |
  | andere negative TEQ | DELIVERED_WITH_ERROR, gilt als zugestellt (11.2.3.2) | REJECTED |
  | sicher nicht angekommen (Verbindung abgelehnt, HTTP 403/404/502–504) | Wiederholung | Wiederholung mit derselben Nummer |
  | keine TEQ, Ankunft unklar (Zeitüberschreitung) | Wiederholung | **UNCLEAR**, die Entscheidung trifft ein Mensch (11.2.3.1) |
  | Wiederholungen erschöpft | UNDELIVERABLE + Mail | UNDELIVERABLE + Mail |

  UNCLEAR und UNDELIVERABLE **blockieren den Schlüssel**, bis jemand erneut sendet, auf „per E-Mail übermittelt“ setzt (11.2.3.2) oder verwirft.
- **Ausfall-Mail** an den technischen Kontakt des Partners und an unseren Betrieb: höchstens einmal je Ausfall und Tag. Nach einer erfolgreichen Zustellung beginnt ein neuer Ausfall mit einer neuen Mail. Die Benachrichtigung wird atomar in `partner_outage` beansprucht, damit sie auch bei mehreren Instanzen nur einmal rausgeht.

## Konsequenzen

- **Datenbankverbindungen:** Während eines Versands (bis 30 s, mit Anlagen bis 120 s) hält der Sender eine Datenbankverbindung. `openspri.outbox.parallelism` (Standard 4) muss deutlich unter der Größe des Verbindungspools liegen (Standard 10).
- **Absturz während des Versands:** Die Transaktion wird zurückgerollt, und der Eintrag wird erneut gesendet. Bei Meldungen ist das zulässig (Meldungen dürfen mehrfach kommen, die letzte gilt). Bei Aufträgen erkennt der Partner die Dublette und antwortet mit ABBM 1010.
- **Blockade durch eine Nachricht:** Eine unzustellbare Nachricht hält alle späteren Nachrichten desselben Auftrags bzw. derselben Leitung an. Das ist gewollt (Reihenfolge vor Durchsatz) und im Zustellmonitor sichtbar (REST ab M4).
