# Ersteinrichtung (Setup)

Eine neue Installation hat noch keinen Benutzer. Bis das Setup abgeschlossen ist, schreibt open-spri beim Start ein **einmaliges Setup-Token** ins Container-Log:

```bash
docker compose logs api | grep -A2 "Setup-Token"
```

Mit diesem Token (Header `X-Setup-Token`) legt `POST /api/v1/setup` in **einer** Transaktion an:
- den ersten Administrator,
- die Firmendaten (Name, ITU Carrier Code, Rollen, Optionen),
- den Signaturschlüssel für WS-Security (neu erzeugen oder einen PKCS#12-Keystore übernehmen).

Danach ist das Setup gesperrt (409). Der Assistent der Web-Oberfläche macht dasselbe.

## `GET /api/v1/setup`: Stand

```bash
curl -s $SPRI/api/v1/setup
```

```json
{"completed": false, "signingKeySource": "TEMPORARY"}
```

`signingKeySource`: `KEYSTORE_FILE` (aus `OPENSPRI_SOAP_SIGNING_KEYSTORE`), `STORED` (gespeichert, verschlüsselt mit `OPENSPRI_MASTER_KEY`) oder `TEMPORARY` (vorläufig, nur bis zum Setup).

## `POST /api/v1/setup`: Setup abschließen

```bash
curl -s -X POST $SPRI/api/v1/setup \
  -H "X-Setup-Token: <token aus dem Log>" \
  -H "Content-Type: application/json" \
  -d '{
    "admin": {"username": "admin", "displayName": "Administration", "password": "ein-sehr-langes-passwort"},
    "company": {
      "name": "Beispiel GmbH",
      "carrierCode": "BSPGMB",
      "contactEmail": "betrieb@example.de",
      "roleAuftraggeber": true,
      "roleLeistungserbringer": true,
      "estEnabled": true,
      "connectivityEnabled": false,
      "thirdPartyOrdersEnabled": false,
      "diagEnabled": true,
      "wbciMode": "SERVICE_PROVIDER",
      "publicSpriEndpoint": "https://spri.example.de/spri/v4",
      "outboundAddresses": ["203.0.113.10"]
    },
    "signingKey": {"generate": true, "commonName": "S/PRI Signatur Beispiel GmbH"}
  }'
```

```json
{"completed": true, "signingKeySource": "STORED"}
```

| Status | Bedeutung |
|---|---|
| 200 | eingerichtet; Anmeldung jetzt mit `POST /api/v1/auth/login` |
| 403 | Setup-Token fehlt oder falsch |
| 409 | Setup ist bereits abgeschlossen |

Einen vorhandenen Schlüssel übernehmen: statt `"generate": true` den Keystore als Base64 übergeben:

```bash
"signingKey": {"pkcs12Base64": "'"$(base64 -w0 spri-signature.p12)"'", "pkcs12Password": "…"}
```

Ein gespeicherter Schlüssel braucht `OPENSPRI_MASTER_KEY` (siehe [`../betrieb.md`](../betrieb.md#signaturschlüssel-ws-security)).

## Einstellungen nach dem Setup

| Aufruf | Scope | Zweck |
|---|---|---|
| `GET /api/v1/settings` | `settings:admin` | Firmendaten und Optionen lesen |
| `PUT /api/v1/settings` | `settings:admin` | ändern (gleiches Format wie `company` oben) |
| `GET /api/v1/settings/signing-key` | `settings:admin` | unser Signaturzertifikat (PEM) für Partner |
| `POST /api/v1/settings/signing-key` | `settings:admin` | Schlüssel wechseln, ohne Neustart: `{}` erzeugt einen neuen, `{"pkcs12Base64": …, "pkcs12Password": …}` übernimmt einen |

Vor einem Schlüsselwechsel brauchen alle Partner das neue Zertifikat, sonst lehnen sie unsere Nachrichten ab.
