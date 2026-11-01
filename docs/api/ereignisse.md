# Ereignisse, Webhooks, Idempotenz

Fremdsysteme erfahren von neuen Aufträgen, eingehenden Meldungen und Zustellproblemen über **Ereignisse**, entweder per Polling (Event-Feed) oder per Webhook (D-14). Besonders wichtig sind TAM, MTAM und AKM-PV, weil dort Fristen laufen.

| Typ | Wann |
|---|---|
| `order.received` | ein Partner hat uns einen Auftrag gesendet (wir sind Leistungserbringer); `findings` enthält Befunde wie Dublette (ABBM 1010) oder R-Regeln |
| `request.received` | Storno, Terminverschiebung oder Reklamation eines Partners |
| `message.received` | eingehende Meldung (QEB, ABM, TAM, AKM-PV, …), auch zu unbekanntem Auftrag (`orderId` null) |
| `order.submitted`, `request.submitted`, `message.submitted` | wir haben etwas in den Postausgang gelegt |
| `delivery.updated` | eine ausgehende Nachricht hat ihren Status geändert (`deliveryStatus`) |

Ereignisse enthalten Kennungen, Status und Meldungscodes, aber **keine Endkundendaten**. Den Auftrag liest man über `GET /orders/{orderId}`.

```json
{
  "id": 1024, "type": "message.received", "partnerId": 7, "orderId": 42, "role": "AUFTRAGGEBER",
  "externalOrderNumber": "AG-2026-0001", "businessCase": "NEU", "phase": "APPOINTMENT_REQUESTED",
  "messageType": "TAM", "changeIndicator": "Standard", "codes": ["0000"], "findings": [],
  "deliveryId": null, "deliveryStatus": null, "at": "2026-11-01T10:05:12Z"
}
```

## Event-Feed (`events:read`)

```bash
curl -s "$SPRI/api/v1/events?after=0&limit=100" -H "Authorization: Bearer $TOKEN"
curl -s "$SPRI/api/v1/events?after=1024&types=message.received,delivery.updated" -H "Authorization: Bearer $TOKEN"
```

Älteste zuerst. Der Client merkt sich die größte `id` und fragt mit `after=<id>` weiter. Ereignisse erscheinen mit etwa **2 s Verzögerung**: So wird keines übersprungen, das in einer parallelen Transaktion gerade noch gespeichert wird. Aufbewahrung: `OPENSPRI_EVENTS_RETENTION` (90 Tage).

## Webhooks (`webhooks:admin`)

```bash
curl -s -X POST $SPRI/api/v1/webhooks -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"url": "https://crm.example.de/hooks/open-spri", "types": ["message.received", "order.received"], "description": "CRM"}'
```

```json
{
  "webhook": {"id": 2, "url": "https://crm.example.de/hooks/open-spri", "types": ["message.received", "order.received"],
              "enabled": true, "description": "CRM", "lastEventId": 1024, "failures": 0,
              "nextAttemptAt": "2026-11-01T10:06:00Z", "lastError": null, "lastSuccessAt": null, "createdAt": "2026-11-01T10:06:00Z"},
  "secret": "whsec_7Jt…"
}
```

- Das Geheimnis wird **nur hier** angezeigt und verschlüsselt gespeichert (braucht `OPENSPRI_MASTER_KEY`). Für ein neues Geheimnis legt man den Webhook neu an.
- Der Empfänger bekommt Ereignisse **ab der Anlage**; ältere liest er über den Feed.
- `types` leer bedeutet alle Typen.
- Ändern mit `PUT /webhooks/{id}` (gleiche Felder), löschen mit `DELETE /webhooks/{id}`. `GET /webhooks/{id}` zeigt `lastError`, `failures` und `nextAttemptAt`.

### Zustellung

Jedes Ereignis kommt als eigener `POST` mit dem Ereignis-JSON als Body:

```
POST /hooks/open-spri HTTP/1.1
Content-Type: application/json
User-Agent: open-spri-webhook
X-OpenSpri-Event: message.received
X-OpenSpri-Event-Id: 1025
X-OpenSpri-Signature: t=1793527572,v1=5f2b…
```

- Als zugestellt gilt nur eine **2xx-Antwort** innerhalb von 10 s. Weiterleitungen werden nicht verfolgt.
- Bei einem Fehler wiederholt open-spri **dasselbe** Ereignis nach 10 s, 20 s, 40 s … (höchstens stündlich). Die folgenden Ereignisse warten, die Reihenfolge bleibt erhalten.
- Der Empfänger sollte doppelte Zustellungen anhand von `X-OpenSpri-Event-Id` erkennen (z. B. nach einem Timeout, obwohl er schon verarbeitet hatte).

### Signatur prüfen

`v1` ist der HMAC-SHA256 über `<t>.<body>` mit dem Geheimnis, hexadezimal. `t` ist die Sendezeit in Unix-Sekunden. Ablehnen sollte man Anfragen mit falscher Signatur oder einem `t`, das mehr als 5 Minuten abweicht.

```bash
# Shell: Body in body.json, Header-Werte in T und V1
printf '%s.' "$T" | cat - body.json | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $2}'   # muss V1 entsprechen
```

```python
import hmac, hashlib, time

def verify(secret: str, header: str, body: bytes) -> bool:
    parts = dict(p.split("=", 1) for p in header.split(","))
    if abs(time.time() - int(parts["t"])) > 300:
        return False
    expected = hmac.new(secret.encode(), parts["t"].encode() + b"." + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, parts["v1"])
```

## Idempotenz

Alle POSTs, die eine S/PRI-Nachricht auslösen (`/orders`, `/orders/{id}/cancel|reschedule|complaint|messages`), akzeptieren den Header `Idempotency-Key`. Fremdsysteme sollten ihn immer setzen, z. B. mit ihrer eigenen Vorgangsnummer:

```bash
curl -s -X POST $SPRI/api/v1/orders/77/messages -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: crm-ticket-5512-abm" -d '{"type": "ABM", "codes": [{"code": "0000"}]}'
```

| Fall | Antwort |
|---|---|
| erster Aufruf mit dem Schlüssel | normal ausgeführt |
| Wiederholung mit gleichem Schlüssel und gleichem Inhalt | gespeicherte Antwort, Header `Idempotent-Replayed: true`, **nichts wird erneut gesendet** |
| gleicher Schlüssel, anderer Inhalt | 422 |
| gleicher Schlüssel, erste Anfrage läuft noch | 409 |
| erste Anfrage endete mit Fehler (4xx/5xx) | Schlüssel ist wieder frei |

Schlüssel gelten je Token bzw. Benutzer und werden 24 h aufbewahrt. Erlaubt sind 1–200 druckbare ASCII-Zeichen ohne Leerzeichen.
