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
  "enabled": true, "allowedSourceNetworks": ["198.51.100.0/28"],
  "certificates": [{"id": 12, "subject": "CN=S/PRI Glasfaser Nord", "issuer": "CN=S/PRI Glasfaser Nord", "serialNumber": "4711",
                    "notAfter": "2028-11-01T00:00:00Z", "keyBits": 3072, "expired": false}]
}
```

- `carrierCode` ist der ITU Carrier Code und wird in Großbuchstaben gespeichert.
- `allowedSourceNetworks`: CIDR-Notation, keine Hostnamen.
- Zertifikate: RSA ≥ 3072 Bit (bestehende 2048-Bit-Zertifikate bis Laufzeitende, AUFGABE 5.3).
- `technicalContactEmail` bekommt die Ausfall-Mail, wenn der Partner nicht erreichbar ist (11.2.3.2).

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
