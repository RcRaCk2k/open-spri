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

## Selbsttests (`settings:admin`)

**`GET /api/v1/selftest/tls`** ruft unseren öffentlichen S/PRI-Endpunkt (`publicSpriEndpoint`) so auf wie ein Partner. Geprüft werden die Anforderungen des AK-Sicherheitsdokuments V3.0, Kap. 2.4 (AUFGABE 7.4):

```bash
curl -s $SPRI/api/v1/selftest/tls -H "Authorization: Bearer $TOKEN"
```

```json
{
  "url": "https://spri.example.de:443/", "ok": true, "protocol": "TLSv1.3",
  "subject": "CN=spri.example.de", "issuer": "CN=E7,O=Let's Encrypt,C=US", "notAfter": "2027-01-30T08:12:44Z",
  "keyType": "EC", "keyBits": 256,
  "checks": [
    {"id": "protocol", "ok": true, "warning": false, "detail": "ausgehandelt: TLSv1.3 (mindestens TLS 1.2)"},
    {"id": "selfSigned", "ok": true, "warning": false, "detail": "von einer CA ausgestellt"},
    {"id": "trust", "ok": true, "warning": false, "detail": "Kette ist vertrauenswürdig (öffentliche CAs)"},
    {"id": "hostname", "ok": true, "warning": false, "detail": "Zertifikat gilt für spri.example.de"},
    {"id": "key", "ok": true, "warning": false, "detail": "EC 256 Bit (gefordert: ECC ≥ 256 oder RSA ≥ 3072)"},
    {"id": "signature", "ok": true, "warning": false, "detail": "Signaturalgorithmus SHA384withECDSA"},
    {"id": "issuer", "ok": true, "warning": false, "detail": "Aussteller CN=E7,O=Let's Encrypt,C=US steht auf der Whitelist"},
    {"id": "validity", "ok": true, "warning": false, "detail": "noch 88 Tage gültig"}
  ]
}
```

- `warning: true` heißt: Es funktioniert, aber bald handeln. Das gilt bei weniger als 14 Tagen Restlaufzeit und wenn keine Aussteller-Whitelist konfiguriert ist (`OPENSPRI_SELFTEST_TRUSTED_ISSUERS`).
- Solange der Proxy noch sein Übergangszertifikat hat, schlagen `selfSigned` und `trust` fehl. In diesem Zustand ist kein Partnerbetrieb möglich.
- Ist der Endpunkt nicht erreichbar, enthält die Antwort nur die Prüfung `connect`.

**`GET /api/v1/selftest/outbound-ip`** fragt den Dienst `OPENSPRI_SELFTEST_IP_ECHO_URL`, von welcher Adresse unsere Anfragen ankommen, und vergleicht sie mit den eingetragenen `outboundAddresses` (AUFGABE 7.6). Die Anfrage nimmt denselben Weg wie S/PRI-Nachrichten, also über den Egress-Proxy, falls einer gesetzt ist. Ohne konfigurierten Dienst antwortet der Endpunkt mit 409. open-spri ruft von sich aus keinen fremden Dienst auf.

```json
{"ok": false, "echoUrl": "https://ip.example.de/", "detected": "203.0.113.25", "configured": ["203.0.113.10"],
 "checks": [{"id": "match", "ok": false, "warning": false,
             "detail": "Anfragen kommen von 203.0.113.25, eingetragen ist 203.0.113.10. Partner würden die Verbindung ablehnen."}]}
```

In der Oberfläche stehen beide Tests unter *Einstellungen → Selbsttests*.
