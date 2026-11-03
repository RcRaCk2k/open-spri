# Partner

Ein Partner ist eine Gegenstelle mit S/PRI-Endpunkt, Signaturzertifikaten und den IP-Adressen, von denen sie uns aufruft. Ohne passendes Zertifikat und freigeschaltete IP lehnt open-spri eingehende Nachrichten ab (AUFGABE 5.3).

## Anlegen (`partners:admin`)

```bash
curl -s -X POST $SPRI/api/v1/partners -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
  "name": "Glasfaser Nord GmbH",
  "carrierCode": "GFNORD",
  "endpointUrl": "https://spri.partner.example/spri/v4",
  "technicalContactEmail": "spri-betrieb@partner.example",
  "allowedSourceNetworks": ["198.51.100.0/28"],
  "certificatesPem": ["-----BEGIN CERTIFICATE-----\nMIID…\n-----END CERTIFICATE-----\n"]
}'
```

```json
{
  "id": 7, "name": "Glasfaser Nord GmbH", "carrierCode": "GFNORD",
  "endpointUrl": "https://spri.partner.example/spri/v4", "technicalContactEmail": "spri-betrieb@partner.example",
  "enabled": true, "useOutboundProxy": true, "allowedSourceNetworks": ["198.51.100.0/28"],
  "certificates": [{"id": 12, "subject": "CN=S/PRI Glasfaser Nord", "issuer": "CN=S/PRI Glasfaser Nord", "serialNumber": "4711",
                    "notAfter": "2028-11-01T00:00:00Z", "keyBits": 3072, "expired": false}]
}
```

- `carrierCode` ist der ITU Carrier Code und wird in Großbuchstaben gespeichert.
- `allowedSourceNetworks`: CIDR-Notation, keine Hostnamen.
- Zertifikate: RSA ≥ 3072 Bit (bestehende 2048-Bit-Zertifikate bis Laufzeitende, AUFGABE 5.3).
- `technicalContactEmail` bekommt die Ausfall-Mail, wenn der Partner nicht erreichbar ist (11.2.3.2).
- `useOutboundProxy` (Standard `true`): Nachrichten an diesen Partner laufen über den Egress-Proxy `SPRI_OUTBOUND_PROXY`, sofern einer gesetzt ist. Mit `false` geht dieser Partner direkt über das Routing bzw. SNAT des Hosts (AUFGABE 7.6), etwa wenn er im selben Netz liegt oder eine andere Quelladresse freigeschaltet hat.

## Ändern

```bash
curl -s -X PATCH $SPRI/api/v1/partners/7 -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"endpointUrl": "https://spri-neu.partner.example/spri/v4"}'                  # nur angegebene Felder
curl -s -X PUT $SPRI/api/v1/partners/7/source-networks -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '["198.51.100.0/28", "2001:db8:10::/48"]'                                         # ersetzt die Liste
curl -s -X PATCH $SPRI/api/v1/partners/7 -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"enabled": false}'                                                               # sperren
```

## Zertifikatswechsel

Während eines Wechsels dürfen mehrere Zertifikate gelten. Neues hinzufügen, nach der Umstellung das alte entfernen:

```bash
curl -s -X POST $SPRI/api/v1/partners/7/certificates -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"pem": "-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----\n"}'          # 201
curl -s -X DELETE $SPRI/api/v1/partners/7/certificates/12 -H "Authorization: Bearer $TOKEN"   # 204
```

## Was der Partner von uns braucht (`partners:read`)

```bash
curl -s $SPRI/api/v1/partners/onboarding-sheet -H "Authorization: Bearer $TOKEN"
curl -s $SPRI/api/v1/partners/wsdl -H "Authorization: Bearer $TOKEN" -o open-spri.wsdl
```

Das Onboarding-Blatt enthält unseren ITU Carrier Code, den S/PRI-Endpunkt, unsere Outbound-IPs zum Freischalten, unser Signaturzertifikat (PEM), den technischen Kontakt und die S/PRI-Version. Die WSDL ist die AK-WSDL, in der nur der Endpunkt auf unseren angepasst ist (Spezifikation 3.3).

## Rahmenverträge, Vollmachten, Projektvereinbarungen

Je Partner hinterlegt man, unter welchen Nummern Aufträge laufen und wer in wessen Namen bestellen darf (AUFGABE 5.7, 5.9). Lesen mit `partners:read`, Ändern mit `partners:admin`. Alle drei haben `validFrom`/`validTo` (einschließlich, leer = offen).

| Pfad | Inhalt | Wirkung |
|---|---|---|
| `/partners/{id}/contracts` | Rahmenvertrag: `role` (unsere Rolle), `clientNumber` (Auftraggebernummer), `serviceNumber` (Leistungsnummer, leer = alle) | Sobald ein Vertrag mit `role=LEISTUNGSERBRINGER` hinterlegt ist, bekommt ein eingehender Auftrag mit unbekannter Nummer den Befund „Rahmenvertrag“ |
| `/partners/{id}/powers-of-attorney` | Vollmacht: Besteller (`ordererClientNumber`, `ordererServiceNumber`) darf für den Auftraggeber (`clientNumber`, `serviceNumber`) bestellen. Der Partner ist der Besteller. | Ein eingehender Auftrag mit Block `besteller` ohne gültige Vollmacht bekommt den Befund R-11 (Grundlage für die ABBM) |
| `/partners/{id}/projects` | Projektvereinbarung: `projectId` (Projektkenner) | Ohne gültige Vereinbarung: eigener Auftrag 409, eingehender Auftrag Befund R-12 |

```bash
curl -s -X POST $SPRI/api/v1/partners/7/contracts -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"role": "LEISTUNGSERBRINGER", "clientNumber": "AG4711", "serviceNumber": "LN0815", "validFrom": "2026-01-01"}'   # 201

curl -s -X POST $SPRI/api/v1/partners/7/powers-of-attorney -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"ordererClientNumber": "B0042", "ordererServiceNumber": "BL0042", "clientNumber": "AG4711", "serviceNumber": "LN0815", "validTo": "2027-12-31"}'

# unterschriebene Vollmacht hinterlegen (PDF, PNG oder JPEG, höchstens 10 MB) und wieder abrufen
curl -s -X PUT "$SPRI/api/v1/partners/7/powers-of-attorney/3/document?name=vollmacht.pdf" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/pdf" --data-binary @vollmacht.pdf                                                       # 204
curl -s $SPRI/api/v1/partners/7/powers-of-attorney/3/document -H "Authorization: Bearer $TOKEN" -o vollmacht.pdf

curl -s -X POST $SPRI/api/v1/partners/7/projects -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"projectId": "PRJ-2026-01", "description": "Ausbau Musterstadt"}'
```

Ändern mit `PUT …/{eintrag}`, löschen mit `DELETE …/{eintrag}` (204). Das Vollmachtsdokument enthält personenbezogene Daten (Unterschriften): Jeder Abruf steht im Audit-Log, und es wird mit der Vollmacht gelöscht. Ein doppelter Projektkenner ergibt 409.

Die Befunde stehen im Verlauf des Auftrags (`GET /orders/{id}/history`, Feld `findings`). Ob daraus eine ABBM wird, entscheidet der Leistungserbringer (Arbeitsvorrat, M5). Rahmenvertrag und Produktkatalog werden erst geprüft, wenn für den Partner Einträge gepflegt sind, damit eine neue Installation nicht jeden Auftrag beanstandet. Vollmacht und Projektkenner werden immer geprüft, weil R-11 und R-12 sie ohne Vereinbarung ausschließen.
