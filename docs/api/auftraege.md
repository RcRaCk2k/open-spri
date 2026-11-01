# Aufträge und Meldungen

Ein Auftrag hat in open-spri immer eine **Rolle** aus unserer Sicht:

| Rolle | Wir sind | Wir senden | Wir empfangen |
|---|---|---|---|
| `AUFTRAGGEBER` | Besteller | Auftrag, Storno, Terminverschiebung, Reklamation; ZWM-AG, TBK-AG, ERLM-K | QEB, ABM, TAM, ERLM, ENTM, … |
| `LEISTUNGSERBRINGER` | Anbieter der Leitung | QEB, ABM, VZM, TAM, MTAM, ZWM-LE, TBK-LE, ERLM, ENTM, ABBM, KDLE | Auftrag, Storno, Terminverschiebung, Reklamation |
| `ABGEBENDER_EKP` | bisheriger Endkundenprovider beim Providerwechsel | RUEM-PV | AKM-PV, ABM-PV, ERLM-PV, ENTM-PV, ABBM-PV |

Jeder Auftrag folgt einem **expliziten Zustandsautomaten** (Grundsatz 6). Was als Nächstes zulässig ist, steht in `allowedActions`. Eine unzulässige Aktion wird mit 409 abgewiesen, und die Antwort nennt die zulässigen Aktionen. Senden heißt immer: prüfen und in den Postausgang legen (202). Die Zustellung läuft asynchron in strenger Reihenfolge je Auftrag bzw. Leitung (siehe [zustellung.md](zustellung.md)).

## Aufträge suchen und lesen (`orders:read`)

```bash
curl -s "$SPRI/api/v1/orders?role=LEISTUNGSERBRINGER&open=true&limit=20" -H "Authorization: Bearer $TOKEN"
curl -s "$SPRI/api/v1/orders?q=AG-2026-0001" -H "Authorization: Bearer $TOKEN"    # Präfix: Auftragsnummer, LineID, Vertragsnummer
```

| Parameter | Bedeutung |
|---|---|
| `role` | `AUFTRAGGEBER`, `LEISTUNGSERBRINGER`, `ABGEBENDER_EKP` |
| `partnerId`, `businessCase` (`NEU`, `KUE-AG`, `EST`, …), `phase` | Filter |
| `q` | Präfixsuche in externer Auftragsnummer, LineID, Vertragsnummer |
| `open=true` | nur nicht abgeschlossene |
| `cursor`, `limit` | Blättern: `cursor` = kleinste `id` der vorigen Seite |

```bash
curl -s $SPRI/api/v1/orders/42 -H "Authorization: Bearer $TOKEN"
```

```json
{
  "order": {
    "id": 42, "partnerId": 7, "role": "AUFTRAGGEBER", "externalOrderNumber": "AG-2026-0001",
    "businessCase": "NEU", "phase": "ACKNOWLEDGED", "finished": false, "openRequests": [], "cancelled": false,
    "handoverPhase": null, "clientNumber": "AG4711", "serviceNumber": "LN0815", "desiredDate": "2026-12-15",
    "contractNumber": null, "lineId": null, "homeId": null, "products": ["FTTH L2 ETH 100.40"],
    "createdAt": "2026-11-01T10:00:00Z", "updatedAt": "2026-11-01T10:05:12Z"
  },
  "allowedActions": [
    {"kind": "REQUEST", "messageType": null, "changeIndicator": null, "request": "STORNO"},
    {"kind": "REQUEST", "messageType": null, "changeIndicator": null, "request": "TERMINVERSCHIEBUNG"}
  ]
}
```

| Aufruf | Inhalt |
|---|---|
| `GET /orders/{id}/allowed-actions` | nur die zulässigen Aktionen |
| `GET /orders/{id}/history` | Verlauf: jeder Auftrag, jede Änderung, jede Meldung mit `direction` (`INBOUND`/`OUTBOUND`), Codes und Befunden der Prüfungen. Eingehende Einträge verweisen mit `archiveId` auf die Rohnachricht (`GET /messages/{archiveId}/raw`). |

```json
[
  {"kind": "ORDER", "direction": "OUTBOUND", "archiveId": null, "messageType": null, "changeIndicator": "Standard", "codes": [], "findings": [], "at": "2026-11-01T10:00:00Z"},
  {"kind": "MESSAGE", "direction": "INBOUND", "archiveId": 815, "messageType": "QEB", "changeIndicator": "Standard", "codes": ["0000"], "findings": [], "at": "2026-11-01T10:05:12Z"}
]
```

## JSON-Format

Aufträge und Meldungsattribute verwenden **die Struktur der AK-XSD** (Anlage 3), eins zu eins als JSON. Es gibt kein zweites, eigenes Auftragsmodell. Wer die Spezifikation kennt, kennt das Format.

- Feldnamen sind die XSD-Elementnamen (`externeAuftragsnummer`, `geschaeftsfall`, `auftragsposition` …).
- Wo die XSD einen abstrakten Typ hat (`xsi:type`), steht im JSON `"@type"` mit dem XSD-Typnamen, z. B. `"BereitstellungType"`, `"ProduktFTTXType"`, `"FTTXBereitstellungType"`.
- Elemente, die mehrfach vorkommen dürfen, sind Arrays; ein einzelnes Objekt wird auch akzeptiert.
- Datum `JJJJ-MM-TT`, Zeitpunkte ISO 8601, Binärdaten Base64.
- Unbekannte Felder sind ein Fehler. Die Antwort nennt den JSON-Pfad und die erlaubten Namen:

```json
{"title": "Ungültiger Inhalt", "status": 400, "detail": "Der Inhalt ist nicht gültig",
 "errors": ["$.auftrag.externeAuftragsnr: unbekanntes Feld in AuftragType; zulässig: aenderungskennzeichen, auftraggeber, externeAuftragsnummer, …"]}
```

Den `control`-Kopf (Zeitstempel, Version, `signaturId` unseres aktuellen Schlüssels) setzt open-spri. Jeder Auftrag wird vor dem Senden gegen die XSD und die Attributregeln aus Anlage 1 geprüft (`errors` beginnt dann mit `XSD:` bzw. `Anlage 1:`).

## Als Auftraggeber

### Auftrag senden (`orders:write`)

```bash
curl -s -X POST $SPRI/api/v1/orders -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: crm-4711-neu" \
  -d '{
  "partnerId": 7,
  "auftrag": {
    "externeAuftragsnummer": "AG-2026-0001",
    "auftraggeber": {"auftraggebernummer": "AG4711", "leistungsnummer": "LN0815"},
    "geschaeftsfall": {
      "@type": "BereitstellungType",
      "ansprechpartner": {"auftragsmanagement": {"anrede": "9", "nachname": "Auftragsmanagement", "telefonnummer": "0123 456789"}},
      "termine": {"auftraggeberWunschtermin": {"datum": "2026-12-15"}},
      "auftragsposition": {
        "produkt": {"@type": "ProduktFTTXType", "bezeichner": "FTTH L2 ETH 100.40"},
        "geschaeftsfallProdukt": {
          "@type": "FTTXBereitstellungType",
          "standortA": {
            "person": {"anrede": "1", "vorname": "Erika", "nachname": "Musterfrau"},
            "strasse": {"strassenname": "Musterstraße", "hausnummer": "1"},
            "postleitzahl": "12345",
            "ort": {"ortsname": "Musterstadt"}
          }
        }
      }
    },
    "geschaeftsfallArt": "Bereitstellung",
    "aenderungskennzeichen": "Standard"
  }
}'
```

Antwort 202 mit dem Auftrag wie bei `GET /orders/{id}`, Phase `SUBMITTED`. Mögliche Fehler:

| Status | Beispiel |
|---|---|
| 400 | Feld unbekannt, XSD-Verstoß, Pflichtfeld nach Anlage 1 fehlt |
| 404 | Partner existiert nicht |
| 409 | Auftragsnummer beim Partner schon vergeben (11.4), Wunschtermin außerhalb der Frist (R-05), Partner deaktiviert |

### Storno, Terminverschiebung, Reklamation

Storno und Terminverschiebung leitet open-spri aus dem ursprünglichen Auftrag ab (Spezifikation 5.1.3/5.1.4). Man gibt nur an, was sich ändert.

```bash
curl -s -X POST $SPRI/api/v1/orders/42/cancel -H "Authorization: Bearer $TOKEN"

curl -s -X POST $SPRI/api/v1/orders/42/reschedule -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"newDate": "2027-01-20"}'

# Reklamation einer abgeschlossenen Entstörung (6.1.5): ein Entstörauftrag mit dem Element reklamation
curl -s -X POST $SPRI/api/v1/orders/43/complaint -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"auftrag": { … "geschaeftsfall": {"@type": "EntstoerungType", …, "reklamation": { … }} … }}'
```

Ob eine Terminverschiebung möglich ist, hängt vom Stand ab: Beim Konnektivitätsauftrag und bei Aufträgen mit Klammer geht sie erst nach der ABM (5.1.2, 4.3.3). Bei einem unzulässigen Zeitpunkt antwortet die API mit 409 und `allowedActions`.

## Meldungen senden

Meldungen gibt man in **vereinfachter Form** an. Kopf (`auftragstyp`), externe Auftragsnummer, Auftraggebernummer und Vertragsnummer übernimmt open-spri aus dem Auftrag, Texte aus der Meldungscodetabelle (SA-011).

```bash
# als Leistungserbringer (Scope le:messages:write): Auftrag bestätigen
curl -s -X POST $SPRI/api/v1/orders/77/messages -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: qeb-77" \
  -d '{"type": "QEB", "codes": [{"code": "0000"}]}'

# ABM mit verbindlichem Liefertermin
curl -s -X POST $SPRI/api/v1/orders/77/messages -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"type": "ABM", "codes": [{"code": "0000"}], "attributes": {"verbindlicherLiefertermin": "2026-12-15"}}'

# Antwort auf einen Storno: changeIndicator des beantworteten Auftrags
curl -s -X POST $SPRI/api/v1/orders/77/messages -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"type": "ERLM", "changeIndicator": "Storno", "codes": [{"code": "0012"}]}'
```

| Feld | Bedeutung |
|---|---|
| `type` | Meldungstyp (`QEB`, `ABM`, `TAM`, `ZWM-AG`, `RUEM-PV`, …) |
| `changeIndicator` | `Standard`, `Storno` oder `Terminverschiebung`: das Änderungskennzeichen des Auftrags, auf den die Meldung antwortet |
| `codes` | mindestens ein Meldungscode; `text` optional, sonst der Text aus der Tabelle |
| `attributes` | weitere `meldungsattribute` des XSD-Typs `Meldungsattribute<Typ>Type` (für ABM z. B. `verbindlicherLiefertermin`, `vorabstimmungId`, `anschluss`) |

Geprüft wird in dieser Reihenfolge: Rolle (wer darf die Meldung senden), Meldungscodes gegen die Tabelle (R-13), Zustandsautomat (409 mit `allowedActions`), danach XSD und Anlage 1 (400).

Scopes: Meldungen in Aufträgen mit der Rolle `LEISTUNGSERBRINGER` brauchen `le:messages:write`, alle anderen `orders:write`.
