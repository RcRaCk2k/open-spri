# Aufträge und Meldungen

Ein Auftrag hat in open-spri immer eine **Rolle** aus unserer Sicht:

| Rolle | Wir sind | Wir senden | Wir empfangen |
|---|---|---|---|
| `AUFTRAGGEBER` | Besteller | Auftrag, Storno, Terminverschiebung, Reklamation; ZWM-AG, TBK-AG, ERLM-K | QEB, ABM, TAM, ERLM, ENTM, … |
| `LEISTUNGSERBRINGER` | Anbieter der Leitung | QEB, ABM, VZM, TAM, MTAM, ZWM-LE, TBK-LE, ERLM, ENTM, ABBM, KDLE | Auftrag, Storno, Terminverschiebung, Reklamation |
| `ABGEBENDER_EKP` | bisheriger Endkundenprovider beim Providerwechsel | RUEM-PV | AKM-PV, ABM-PV, ERLM-PV, ENTM-PV, ABBM-PV |

Jeder Auftrag folgt einem **expliziten Zustandsautomaten** (Grundsatz 6). Was als Nächstes zulässig ist, steht in `allowedActions` (`MESSAGE`, `REQUEST` oder `SEND` für einen Entwurf). Eine unzulässige Aktion wird mit 409 abgewiesen, und die Antwort nennt die zulässigen Aktionen. Senden heißt immer: prüfen und in den Postausgang legen (202). Die Zustellung läuft asynchron in strenger Reihenfolge je Auftrag bzw. Leitung (siehe [zustellung.md](zustellung.md)).

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

### Auftrag anlegen und senden (`orders:write`)

Ein neuer Auftrag ist zuerst ein **Entwurf** (AUFGABE 5.6: Entwurf → Senden). `POST /orders` legt ihn an (201, Phase `DRAFT`); dabei prüft open-spri schon XSD und Anlage 1 und reserviert die externe Auftragsnummer. Gesendet wird er mit `POST /orders/{id}/send`. Wer keinen Zwischenschritt braucht (z. B. ein CRM), hängt `?send=true` an: Dann wird in einem Schritt geprüft und gesendet (202).

```bash
curl -s -X POST "$SPRI/api/v1/orders?send=true" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
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

#### Auftrag aus einfachen Angaben zusammensetzen

Wer die XSD-Struktur nicht selbst bauen will, lässt sie von open-spri zusammensetzen. `POST /orders/compose` liefert das `auftrag`-JSON des Geschäftsfalls mit allen `@type`-Angaben. Es wird weder geprüft noch gespeichert. `missing` nennt die XSD-Pfade, die der Geschäftsfall noch braucht. Die Web-Oberfläche baut ihren Auftragsassistenten genau so.

```bash
curl -s -X POST $SPRI/api/v1/orders/compose -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "businessCase": "KUE-AG", "externalOrderNumber": "KUE-2026-0001", "clientNumber": "AG4711", "serviceNumber": "LN0815",
  "desiredDate": "2027-01-31", "contact": {"lastName": "Auftragsmanagement", "phone": "0123 456789"},
  "contractNumber": "V4711", "lineId": "DEU.GFNORD.0000000001"
}'
```

```json
{"auftrag": {"externeAuftragsnummer": "KUE-2026-0001", "auftraggeber": {…}, "geschaeftsfall": {"@type": "KuendigungType", "vertragsnummer": "V4711", …},
             "geschaeftsfallArt": "Kuendigung", "aenderungskennzeichen": "Standard"},
 "missing": []}
```

| Feld | Geschäftsfälle | wird zu |
|---|---|---|
| `businessCase` | alle außer KUE-LE | `@type` des Geschäftsfalls und `geschaeftsfallArt` |
| `externalOrderNumber`, `clientNumber`, `serviceNumber` | alle | `externeAuftragsnummer`, `auftraggeber` |
| `desiredDate`, `timeWindow` | alle außer DIAG | `termine/auftraggeberWunschtermin` (`datum`, `zeitfenster`) |
| `contact` (`lastName`, `phone` Pflicht) | alle außer DIAG | `ansprechpartner/auftragsmanagement` |
| `contractNumber`, `lineId` | KUE-AG, LAE, AEN-LMAE, EST, DIAG; `lineId` auch PV | `vertragsnummer`, `lineId` |
| `preAgreementId` | PV (Pflicht), NEU, KUE-AG | `vorabstimmungId` |
| `products` | NEU, PV, LAE, AEN-LMAE | erstes: `auftragsposition/produkt`; weitere: `position` (bei AEN-LMAE mit `aktionscode` A) |
| `siteParty`, `site`, `homeId` | NEU, PV | `standortA` mit `person` oder `firma`, `vormieter/homeId` |
| `fault` | EST | `stoerungsNrAuftraggeber`, `stoerungsbeschreibung`, `vorpruefungErfolgt`, `kontaktEndkunde` |
| `operations` | DIAG-GET, DIAG-SET | `auftragsposition/parameter/operation` |

Das Ergebnis geht unverändert oder ergänzt an `POST /orders`.

#### Fehlerhafte Aufträge für KFT-Negativtests (ADR-0008)

Einige KFT-Testfälle verlangen, dass wir als Auftraggeber absichtlich einen fehlerhaften Auftrag senden, damit der Leistungserbringer mit ABBM antwortet. open-spri weist solche Aufträge normalerweise mit 409 ab. Mit einem Token, das den Scope `orders:override` trägt, lassen sich einzelne Regeln übersteuern:

| `override` | erlaubt | KFT |
|---|---|---|
| `R-05` | Wunschtermin 180 Tage oder mehr voraus | Z.2.2.01, Z.2.2.02 |
| `GESCHAEFTSFALLART` | Geschäftsfallart passt nicht zum Geschäftsfall | Z.5.2.01 |
| `AENDERUNGSKENNZEICHEN` | neuer Auftrag mit „Storno“ oder „Terminverschiebung“ | Z.4.2.01 |

```bash
# Token mit dem Scope ausstellen (nur ADMIN, ausdrücklich angeben)
curl -s -X POST $SPRI/api/v1/tokens -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "KFT", "role": "ADMIN", "scopes": ["orders:read", "orders:write", "orders:override"], "expiresAt": "2026-12-31T23:59:59Z"}'

curl -s -X POST "$SPRI/api/v1/orders?send=true&override=R-05" -H "Authorization: Bearer $KFT_TOKEN" -H "Content-Type: application/json" \
  -d @kuendigung-in-200-tagen.json
```

XSD, Anlage 1 und Zustandsautomat gelten immer. Die Übersteuerung steht im Audit-Log und als Befund „Übersteuert (ADR-0008): …“ im Verlauf. Ohne den Scope antwortet die API mit 403, bei einem unbekannten Wert mit 400.

#### Entwürfe

```bash
# anlegen: 201, Phase DRAFT, allowedActions = [SEND]
DRAFT=$(curl -s -X POST $SPRI/api/v1/orders -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d @neu-auftrag.json | jq .order.id)

curl -s $SPRI/api/v1/orders/$DRAFT/draft -H "Authorization: Bearer $TOKEN"          # Inhalt wie gespeichert
curl -s -X PUT $SPRI/api/v1/orders/$DRAFT -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d @neu-auftrag-v2.json                         # Inhalt ersetzen
curl -s -X POST $SPRI/api/v1/orders/$DRAFT/send -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: entwurf-$DRAFT"                                                # senden: 202, Phase SUBMITTED
curl -s -X DELETE $SPRI/api/v1/orders/$DRAFT -H "Authorization: Bearer $TOKEN"       # oder verwerfen: 204
```

- Ein Entwurf muss ein **vollständiger, gültiger Auftrag** sein (XSD, Anlage 1). Halbfertige Eingaben hält das Frontend selbst.
- Die **Auftragsregeln** (z. B. Wunschtermin R-05) prüft open-spri beim Senden, weil sie vom Datum abhängen. Wird der Entwurf abgewiesen (409), bleibt er Entwurf.
- Beim Senden wird der Auftrag mit aktuellem Kopf und aktuellem Signaturschlüssel neu gebaut.
- Ein Entwurf hat keinen Verlauf und keine Ereignisse. Das Ereignis `order.submitted` entsteht beim Senden.
- Ändern, Löschen und Senden geht nur im Entwurf; danach antwortet die API mit 409.

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

## Als Leistungserbringer: Kündigung (KUE-LE, KDLE)

Die Kündigung durch den Leistungserbringer beginnt ohne Auftrag des Partners: Die KDLE ist laut Arbeitshandbuch eine „eigenständige Meldung (KEIN Auftrag)“. `POST /terminations` legt den Auftrag `KUE-LE` in der Rolle `LEISTUNGSERBRINGER` an und sendet die KDLE. Die externe Auftragsnummer vergeben wir selbst (Anlage 1: „GF KUE-LE: wird in diesem Fall vom LE eineindeutig vergeben“). ERLM und ENTM folgen wie gewohnt über `POST /orders/{id}/messages`.

```bash
curl -s -X POST $SPRI/api/v1/terminations -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: kdle-2026-0001" \
  -d '{"partnerId": 7, "externalOrderNumber": "KDLE-2026-0001", "contractNumber": "V4711", "clientNumber": "AG4711",
       "shutdownDate": "2027-01-31", "reason": "Zahlungsverzug", "codes": [{"code": "8006"}]}'
```

| Feld | KDLE-Element | |
|---|---|---|
| `externalOrderNumber` | `externeAuftragsnummer` | Pflicht, je Partner eindeutig |
| `contractNumber`, `clientNumber` | `vertragsnummer`, `auftraggebernummer` | Pflicht |
| `shutdownDate` | `abschaltungstermin` | Pflicht |
| `reason` | `abschaltungsgrund` | optional |
| `lineId` | – | optional, nicht Teil der KDLE: ordnet den Postausgang mit anderen Nachrichten zur Leitung und hilft bei der Suche |
| `codes`, `attributes` | Meldungscodes, weitere `meldungsattribute` | wie bei Meldungen |

Scope `le:messages:write`. 409, wenn die Nummer beim Partner schon vergeben ist oder der Meldungscode nicht zu KDLE passt.

## Als abgebender EKP: Providerwechsel-Anfragen (AKM-PV, RUEM-PV)

Wirbt ein anderer Provider einen unserer Kunden ab, schickt uns der Leistungserbringer eine AKM-PV. open-spri legt dafür einen Auftrag in der Rolle `ABGEBENDER_EKP` an. Die AKM-PV hat eine **Antwortfrist**; bis dahin muss die RUEM-PV gesendet sein.

```bash
# offene Anfragen (RUEM-PV steht aus); ?open=false zeigt alle
curl -s $SPRI/api/v1/pv-requests -H "Authorization: Bearer $TOKEN"
```

```json
[{
  "order": {"id": 51, "role": "ABGEBENDER_EKP", "externalOrderNumber": "PV-AUF-0001", "businessCase": "PV", "handoverPhase": "ANNOUNCED", …},
  "replyDue": true, "announcementArchiveId": 902, "newProvider": "Beispiel Telekommunikation",
  "plannedTransferDate": "2026-12-15", "replyDeadline": "2026-11-08", "lineId": "DEU.GFNORD.0000000001"
}]
```

Antworten (Scope `orders:write`):

```bash
curl -s -X POST $SPRI/api/v1/pv-requests/51/reply -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: ruem-51" \
  -d '{"consent": true, "providerName": "Unser Netz", "codes": [{"code": "0021"}]}'
```

`consent` wird zu `zustimmungProviderwechsel` (`J`/`N`), dazu optional `answerCode` und `answerText` (`antwortcode`, `antworttext`). Vertragsnummer, externe Auftragsnummer, Auftraggebernummer, VorabstimmungsID und `anschluss` übernimmt open-spri aus der archivierten AKM-PV, weil sie in der RUEM-PV dieselben sein müssen. Danach kommen ABM-PV, ERLM-PV, ENTM-PV (oder ABBM-PV) als Ereignisse `message.received`. Eine zweite RUEM-PV wird mit 409 abgewiesen.
