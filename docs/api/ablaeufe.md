# Abläufe Ende zu Ende

Die Beispiele zeigen typische Abläufe nur mit der REST-API. Genauso arbeitet die Web-Oberfläche. Vorausgesetzt: Setup erledigt ([setup.md](setup.md)), Partner angelegt ([partner.md](partner.md)), Meldungscodetabelle importiert ([meldungscodes.md](meldungscodes.md)), Token in `$TOKEN` ([auth.md](auth.md)).

## 1. NEU-Auftrag als Auftraggeber bis zur ENTM

```bash
# 1. Auftrag in einem Schritt anlegen und senden (Inhalt wie in auftraege.md); merkt sich die id
#    Ohne ?send=true entsteht ein Entwurf, der mit POST /orders/{id}/send gesendet wird.
ORDER=$(curl -s -X POST "$SPRI/api/v1/orders?send=true" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: crm-4711-neu" -d @neu-auftrag.json | jq .order.id)

# 2. Zustellung prüfen: DELIVERED mit teqCode OK = der Partner hat technisch quittiert
curl -s "$SPRI/api/v1/deliveries?orderId=$ORDER" -H "Authorization: Bearer $TOKEN" | jq '.[0] | {status, teqCode}'
```

Danach kommen die Meldungen des Partners. Statt zu pollen, wartet man auf Ereignisse:

```bash
AFTER=0
while true; do
  EVENTS=$(curl -s "$SPRI/api/v1/events?after=$AFTER&types=message.received,delivery.updated" -H "Authorization: Bearer $TOKEN")
  echo "$EVENTS" | jq -c '.[] | {id, type, orderId, messageType, codes, phase}'
  AFTER=$(echo "$EVENTS" | jq --argjson a $AFTER 'map(.id) | max // $a')
  sleep 10
done
```

Typische Folge für unseren Auftrag:

| Ereignis | Bedeutung | Phase danach | Was tun |
|---|---|---|---|
| `message.received` QEB 0000 | Auftrag angenommen | `ACKNOWLEDGED` | |
| `message.received` TAM | Partner braucht einen Termin | `APPOINTMENT_REQUESTED` | Endkunden kontaktieren, Termin über die Prozesse des Partners abstimmen |
| `message.received` ABM | Liefertermin bestätigt, LineID vergeben | `CONFIRMED` | `lineId` und Termin aus `GET /orders/$ORDER` übernehmen |
| `message.received` ERLM | Leitung bereitgestellt | `COMPLETED` | Abnahme beim Endkunden |
| `message.received` ENTM | Abrechnung beginnt, Auftrag beendet | `CLOSED` | |

Zwischendurch möglich:

```bash
# Termin verschieben (nach der QEB; bei Konnektivität und Klammer erst nach der ABM)
curl -s -X POST $SPRI/api/v1/orders/$ORDER/reschedule -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"newDate": "2027-01-20"}'
# stornieren: der Partner antwortet mit ERLM (0012) und später ENTM
curl -s -X POST $SPRI/api/v1/orders/$ORDER/cancel -H "Authorization: Bearer $TOKEN"
```

Ist eine Aktion gerade nicht erlaubt (z. B. zweiter Storno), antwortet die API mit 409, und `allowedActions` zeigt, was stattdessen geht.

## 2. Eingehenden Auftrag als Leistungserbringer bearbeiten

```bash
# neue Aufträge von Partnern
curl -s "$SPRI/api/v1/events?after=$AFTER&types=order.received" -H "Authorization: Bearer $TOKEN" | jq -c '.[] | {orderId, externalOrderNumber, findings}'
ORDER=77
curl -s $SPRI/api/v1/orders/$ORDER -H "Authorization: Bearer $TOKEN" | jq '{order: .order | {externalOrderNumber, businessCase, desiredDate, products}, allowedActions}'
curl -s $SPRI/api/v1/orders/$ORDER/history -H "Authorization: Bearer $TOKEN" | jq '.[0].findings'   # Befunde der Eingangsprüfung
```

Ohne Befunde wird der Auftrag bestätigt, mit Befunden abgelehnt:

```bash
# annehmen
curl -s -X POST $SPRI/api/v1/orders/$ORDER/messages -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: le-$ORDER-qeb" -d '{"type": "QEB", "codes": [{"code": "0000"}]}'

# oder ablehnen, z. B. Dublette (11.4)
curl -s -X POST $SPRI/api/v1/orders/$ORDER/messages -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"type": "ABBM", "codes": [{"code": "1010"}]}'

# Liefertermin bestätigen
curl -s -X POST $SPRI/api/v1/orders/$ORDER/messages -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: le-$ORDER-abm" -d '{"type": "ABM", "codes": [{"code": "0000"}], "attributes": {"verbindlicherLiefertermin": "2026-12-15"}}'

# fertig gestellt, danach Ende der Bereitstellung
curl -s -X POST $SPRI/api/v1/orders/$ORDER/messages -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"type": "ERLM", "codes": [{"code": "0010"}]}'
curl -s -X POST $SPRI/api/v1/orders/$ORDER/messages -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"type": "ENTM", "codes": [{"code": "0010"}]}'
```

Kommt ein Storno des Partners (`request.received` mit `changeIndicator` `Storno`), beantwortet man ihn mit `"changeIndicator": "Storno"` (siehe [auftraege.md](auftraege.md#meldungen-senden)).

Welche Codes für welchen Meldungstyp zulässig sind, zeigt `GET /api/v1/message-codes?messageType=ERLM`. Die Codes oben sind Beispiele; maßgeblich ist die Tabelle des AK.

## 3. Zustellproblem beheben

```bash
# Ereignis delivery.updated mit deliveryStatus UNDELIVERABLE oder UNCLEAR
curl -s "$SPRI/api/v1/deliveries?status=UNDELIVERABLE" -H "Authorization: Bearer $TOKEN" | jq -c '.[] | {id, externalOrderNumber, lastError}'
curl -s -X POST $SPRI/api/v1/deliveries/311/retry -H "Authorization: Bearer $TOKEN"
```

Ein **Auftrag** im Status `UNCLEAR` (keine TEQ) wird nie mit derselben Nummer erneut gesendet (NF-05): beim Partner nachfragen, mit neuer Auftragsnummer neu stellen und den alten Eintrag mit `cancel` zurückziehen.
