# ADR-0010: Aufbewahrung von Endkundendaten – Inhalte löschen, Nachweis behalten

Status: angenommen | Datum: 2026-11-02 | Bezug: AUFGABE NF-09, NF-10, Grundsatz 13

## Kontext

NF-10 verlangt, dass die Aufbewahrung von Endkundendaten (Name, Anschrift, Kontakt, Eigentümer) konfigurierbar ist und dass abgeschlossene Aufträge nach X Monaten pseudonymisiert bzw. gelöscht werden. NF-09 verlangt dagegen ein **revisionssicheres** Archiv, also jede SOAP-Nachricht roh und mit Signatur.

Die Endkundendaten stehen nicht in eigenen Spalten. Sie stecken in den Nachrichteninhalten:
- im Archiv (`soap_archive.request`)
- im Eingang (`inbox_message.payload`)
- im Postausgang (`outbox_message.payload`)
- in Entwürfen (`spri_order_draft.content`)
- im normalisierten Adressschlüssel für R-10 (`spri_order.infrastructure_key`)

Eine Pseudonymisierung *innerhalb* der signierten XML-Nachricht würde die Signatur brechen. Danach wäre die Nachricht kein Nachweis mehr, sondern nur noch eine veränderte Kopie.

## Entscheidung

- **Löschen statt Pseudonymisieren.** Nach Ablauf der Frist wird der **Inhalt** entfernt:
  - Archiv-Anfrage, Eingangs- und Postausgangs-Payload, Entwürfe, Adressschlüssel
  - Erhalten bleiben alle Metadaten: Zeit, Richtung, Partner, Auftragsnummer, TEQ, Status, Prüfergebnisse.
  - Ebenso erhalten bleiben die TEQ-Antwort (enthält keine Endkundendaten) und der **SHA-256 der Rohnachricht** (`request_sha256`). Wer die Nachricht später vorlegt, etwa der Partner, kann so beweisen, dass sie unverändert ist.
- **Abgeschlossen** heißt: Es gibt keinen Auftrag mit derselben Partner- und externen Auftragsnummer, der offen ist oder nach dem Stichtag abgeschlossen wurde. Die Frist läuft also ab Abschluss des Auftrags, nicht ab Eingang der Nachricht. Abgelehnte Anfragen ohne Auftrag fallen unter dieselbe Frist ab Eingang.
- **Konfiguration:** `OPENSPRI_RETENTION_PERSONAL_DATA`, eine Dauer wie `730d`. **Standard ist `0` (aus).** Ob und wann gelöscht werden darf, hängt von Aufbewahrungspflichten des Betreibers ab, etwa aus Handels- und Steuerrecht oder aus Verträgen mit Partnern. Das kann die Software nicht für ihn entscheiden. Die Betriebsdoku fordert die Einstellung bei der Inbetriebnahme ausdrücklich an.
- **Lauf:** täglich um 02:30 Uhr (`OPENSPRI_RETENTION_CRON`), idempotent, mit Ergebnis im Log.
- **Sichtbarkeit:** `GET /api/v1/messages/{id}` zeigt `contentPurgedAt` und `requestSha256`. `GET /api/v1/messages/{id}/raw` antwortet dann mit **410 Gone**.

## Konsequenzen

- Nach der Frist lässt sich eine Nachricht nicht mehr inhaltlich anzeigen oder erneut verarbeiten. Ein Storno oder eine Terminverschiebung braucht den Originalauftrag, betrifft aber nur offene Aufträge und ist daher nicht berührt.
- Ereignisse (Feed, Webhooks) enthalten keine Endkundendaten und haben ihre eigene Frist (`OPENSPRI_EVENTS_RETENTION`).
- Vollmachten (`partner_power_of_attorney.document`) betreffen Partner, nicht Endkunden. Sie verwaltet der Betreiber über die Partnerseite.
- Backups enthalten die Daten weiter, bis sie rotiert sind. Die Betriebsdoku nennt das.

## Alternativen

- **Pseudonymisieren im XML** (Namen durch Platzhalter ersetzen): Das bricht die Signatur und erfordert eine Liste aller personenbezogenen Elemente je Nachrichtentyp, die mit jeder XSD-Version gepflegt werden muss. Verworfen.
- **Archiv verschlüsseln und den Schlüssel je Zeitraum löschen** („crypto shredding“): Das ist elegant, aber deutlich aufwendiger, und das Archiv wäre nicht mehr per SQL auswertbar. Für v1 verworfen, als spätere Option offen.
- **Fester Standard, z. B. 24 Monate:** Das wäre datensparsam, könnte aber unbemerkt gegen Aufbewahrungspflichten des Betreibers verstoßen. Verworfen zugunsten einer bewussten Einstellung.
