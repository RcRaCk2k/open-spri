# Bestand

Der Bestand sind die Verträge, die aus abgeschlossenen Aufträgen entstehen (AUFGABE 5.6). Er ist die Grundlage für Kündigung, Leistungsmerkmal- und Leistungsänderung, Entstörung und den Providerwechsel. Lesen mit `orders:read`.

| Ereignis | Wirkung auf den Bestand |
|---|---|
| ENTM zu NEU oder PV | neuer Vertrag (Vertragsnummer und LineID aus der ABM); ein bisheriger Vertrag derselben Rolle auf derselben Leitung endet |
| ENTM zu KUE-AG oder KUE-LE | der Vertrag endet |
| ENTM-PV (wir als abgebender EKP) | unser Vertrag auf der Leitung endet |
| Storno (ERLM 0012, ENTM) | keine Änderung |

`role` ist unsere Rolle im Vertrag: `AUFTRAGGEBER` heißt, wir nutzen die Leitung des Partners. `LEISTUNGSERBRINGER` heißt, der Partner nutzt unsere Leitung.

```bash
curl -s "$SPRI/api/v1/inventory?role=LEISTUNGSERBRINGER&partnerId=7" -H "Authorization: Bearer $TOKEN"
curl -s "$SPRI/api/v1/inventory/DEU.GFNORD.0000000001" -H "Authorization: Bearer $TOKEN"   # alle Verträge der Leitung, auch beendete
```

```json
[{"id": 12, "role": "LEISTUNGSERBRINGER", "partnerId": 7, "contractNumber": "V4711", "lineId": "DEU.GFNORD.0000000001",
  "clientNumber": "AG4711", "serviceNumber": "LN0815", "products": ["FTTH 100"], "orderId": 42,
  "startedAt": "2026-11-01T10:00:00Z", "endedAt": null}]
```

| Parameter | Bedeutung |
|---|---|
| `role`, `partnerId`, `lineId` | Filter |
| `active=false` | auch beendete Verträge |
| `cursor`, `limit` | Blättern: `cursor` = kleinste `id` der vorigen Seite |

## Providerwechsel als Leistungserbringer

Bei einem PV-Auftrag sind wir Leistungserbringer und kündigen den Wechsel dem **abgebenden EKP** an (AKM-PV, Spezifikation 5.5.1). Den abgebenden EKP bestimmt open-spri aus dem Bestand: Es ist der Inhaber des aktiven Vertrags auf der LineID des Auftrags. Er wird mit der AKM-PV am Auftrag festgehalten.

AKM-PV, ABM-PV, ERLM-PV, ENTM-PV und ABBM-PV gehen dann an diesen Partner, mit **seiner** Vertrags- und Auftraggebernummer und der externen Auftragsnummer des aufnehmenden EKP (Anlage 1). Man sendet sie wie jede Meldung über `POST /orders/{id}/messages`. Gibt es zur LineID keinen aktiven Vertrag, antwortet die API mit 409: Der abgebende EKP ist dann unbekannt. Seine RUEM-PV ordnet open-spri über die externe Auftragsnummer dem Auftrag zu.
