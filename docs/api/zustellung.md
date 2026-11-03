# Zustellung, Eingang, Archiv

## Postausgang (`deliveries:read`, `deliveries:write`)

Alles, was open-spri sendet, liegt zuerst im Postausgang. Je Auftrag bzw. Leitung geht eine Nachricht erst raus, wenn die vorige abgeschlossen ist (Grundsatz 7, ADR-0007).

```bash
curl -s "$SPRI/api/v1/deliveries?orderId=42" -H "Authorization: Bearer $TOKEN"
curl -s "$SPRI/api/v1/deliveries?status=UNDELIVERABLE" -H "Authorization: Bearer $TOKEN"
```

```json
[{
  "id": 311, "partnerId": 7, "orderId": 42, "type": "ORDER", "operation": "annehmenAuftrag",
  "externalOrderNumber": "AG-2026-0001", "status": "DELIVERED", "attempts": 1,
  "nextAttemptAt": "2026-11-01T10:00:00Z", "lastError": null, "teqCode": "OK",
  "createdAt": "2026-11-01T10:00:00Z", "finishedAt": "2026-11-01T10:00:01Z"
}]
```

| Status | Bedeutung | Eingriff |
|---|---|---|
| `PENDING` | wartet bzw. wird wiederholt | `cancel` |
| `DELIVERED` | positive TEQ | |
| `DELIVERED_WITH_ERROR` | Meldung mit negativer TEQ; gilt als zugestellt (11.2.3.2) | mit dem Partner klären |
| `REJECTED` | Auftrag mit negativer TEQ | Auftrag korrigieren, neu stellen |
| `UNCLEAR` | keine TEQ: der Auftrag könnte angekommen sein | beim Partner klären; **mit neuer Nummer** neu stellen und `cancel` (NF-05). Meldungen: `retry` |
| `UNDELIVERABLE` | Wiederholungen erschöpft | `retry`, `sent-by-email` oder `cancel` |
| `SENT_BY_EMAIL` | über den vereinbarten E-Mail-Weg übermittelt | |
| `CANCELLED` | zurückgezogen; die folgenden Nachrichten desselben Auftrags laufen weiter | |

```bash
curl -s -X POST $SPRI/api/v1/deliveries/311/retry -H "Authorization: Bearer $TOKEN"
curl -s -X POST $SPRI/api/v1/deliveries/311/sent-by-email -H "Authorization: Bearer $TOKEN"
curl -s -X POST $SPRI/api/v1/deliveries/311/cancel -H "Authorization: Bearer $TOKEN"
curl -s $SPRI/api/v1/deliveries/311/payload -H "Authorization: Bearer $TOKEN"   # XML vor dem Signieren (auch orders:read; wird protokolliert)
```

Ist der Eingriff im aktuellen Status nicht möglich, antwortet die API mit 409. Jeder Eingriff erzeugt das Ereignis `delivery.updated` und einen Eintrag im Audit-Log.

## Eingang

Eingehende Nachrichten werden sofort geprüft, archiviert und quittiert (TEQ). Die fachliche Verarbeitung läuft danach (Grundsatz 8).

```bash
curl -s "$SPRI/api/v1/inbox?status=FAILED" -H "Authorization: Bearer $TOKEN"
curl -s -X POST $SPRI/api/v1/inbox/90/reprocess -H "Authorization: Bearer $TOKEN"   # nur FAILED
```

| Status | Bedeutung |
|---|---|
| `RECEIVED` | quittiert, Verarbeitung ausstehend |
| `PROCESSED` | verarbeitet; `findings` nennt Auffälligkeiten (z. B. Meldung passt nicht zum Ablauf, Anlage 1 im Report-Modus) |
| `FAILED` | Fehler in der Verarbeitung (`error`). Blockiert weitere Nachrichten desselben Auftrags, bis sie wiederholt wird. |

## Nachrichtenarchiv (`orders:read`)

Jede SOAP-Nachricht wird in beiden Richtungen byte-genau mit Antwort archiviert (NF-09).

```bash
curl -s $SPRI/api/v1/messages/815 -H "Authorization: Bearer $TOKEN"            # Metadaten
curl -s $SPRI/api/v1/messages/815/raw -H "Authorization: Bearer $TOKEN"        # SOAP-Envelope mit Signatur
curl -s $SPRI/api/v1/messages/815/response -H "Authorization: Bearer $TOKEN"   # unsere bzw. ihre TEQ
```

```json
{
  "id": 815, "direction": "INBOUND", "operation": "annehmenMeldung", "partnerId": 7, "remoteAddress": "198.51.100.3",
  "startedAt": "2026-11-01T10:05:12Z", "durationMillis": 38, "teqCode": "OK", "rejectionReason": null,
  "findings": null, "signerSubject": "CN=S/PRI Glasfaser Nord", "externalOrderNumber": "AG-2026-0001",
  "requestSha256": "9f2c…e41a", "contentPurgedAt": null
}
```

`rejectionReason` erklärt eine negative TEQ intern (z. B. „Signatur ungültig“). Der Partner erhält laut Spezifikation nur die Fehlernummer. Rohnachrichten enthalten Endkundendaten; jeder Abruf von `/raw` steht im Audit-Log.

**Aufbewahrung (NF-10, ADR-0010):** Ist `OPENSPRI_RETENTION_PERSONAL_DATA` gesetzt, löscht open-spri den Inhalt abgeschlossener Vorgänge nach dieser Frist. `contentPurgedAt` zeigt dann den Zeitpunkt, und `/raw` antwortet mit `410 Gone`. Metadaten, TEQ-Antwort und `requestSha256` bleiben erhalten. Mit dem Hash lässt sich eine später vorgelegte Kopie der Nachricht prüfen (`sha256sum`).

## Wartung ankündigen (`partners:admin`)

Wartungsarbeiten, die die Schnittstelle beeinträchtigen, sind allen Partnern mit **5 Werktagen Vorlauf** per Mail anzuzeigen (Spezifikation 11.5, NF-08).

```bash
curl -s -X POST $SPRI/api/v1/maintenance-announcements -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"start": "2026-11-20T20:00:00Z", "end": "2026-11-20T22:00:00Z", "subject": "Wartung der S/PRI-Schnittstelle",
       "text": "Wir spielen eine neue Version ein."}'
```

```json
{"id": 4, "start": "2026-11-20T20:00:00Z", "end": "2026-11-20T22:00:00Z", "subject": "Wartung der S/PRI-Schnittstelle",
 "text": "Wir spielen eine neue Version ein.", "partnerIds": [7, 9], "recipients": 1,
 "warnings": ["Partner Glasfaser Süd hat keine Kontaktadresse"], "createdAt": "2026-11-02T09:00:00Z", "createdBy": "user:admin"}
```

- Ohne `partnerIds` geht die Mail an alle aktiven Partner, sonst nur an die genannten.
- Empfänger ist der technische Kontakt des Partners, in Kopie `OPENSPRI_NOTIFICATIONS_OPERATOR_EMAIL`.
- Eine kürzere Frist ist möglich (z. B. für eine dringende Korrektur), steht aber in `warnings`. Werktage sind Montag bis Freitag; Feiertage berücksichtigt open-spri nicht, weil sie je Bundesland verschieden sind.
- `GET /maintenance-announcements` (`partners:read`) listet die bisherigen Ankündigungen, neueste zuerst.
