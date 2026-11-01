# Betrieb von open-spri

> Stand M0. Dieses Handbuch wächst mit jedem Meilenstein und ist bis M8 vollständig (Backup/Restore, Updates, Zertifikatswechsel, Monitoring).

## Voraussetzungen

- Linux-Server oder VM mit Docker Engine und Docker Compose v2
- Ein DNS-Name (z. B. `spri.example.de`), der auf die **Inbound-IP** des Servers zeigt
- Ports **80** (Let's Encrypt-Prüfung, Umleitung) und **443** aus dem Internet erreichbar
- Eine feste **Outbound-IP** für Verbindungen zu Partnern. Partner schalten diese IP frei (AK-Sicherheitsdokument, Kap. 2.6). Inbound- und Outbound-IP dürfen verschieden sein (AUFGABE Kap. 7.6).

## Variante A: All-in-One (empfohlen)

```bash
git clone https://github.com/RcRaCk2k/open-spri.git
cd open-spri
cp .env.example .env
# .env anpassen: DB_PASSWORD, DB_ROOT_PASSWORD, PUBLIC_HOSTNAME, ACME_EMAIL
docker compose up -d --build
```

Was passiert:
1. `db` (MariaDB) startet. `api` legt beim ersten Start das Datenbankschema an (Flyway).
2. `proxy` (nginx) startet mit einem **vorläufigen, selbst signierten Zertifikat**.
3. `certbot` holt das Let's-Encrypt-Zertifikat über die HTTP-01-Prüfung (Schlüssel ECDSA P-256).
4. Der Proxy erkennt das neue Zertifikat innerhalb von 5 Minuten und lädt ohne Unterbrechung neu. Danach wird alle 12 Stunden auf Verlängerung geprüft.

**Staging zuerst:** In `.env.example` ist `ACME_STAGING=true` voreingestellt. Damit gibt Let's Encrypt Testzertifikate aus, ohne Ratenlimits. Sobald alles läuft, `ACME_STAGING=false` setzen und `docker compose up -d` ausführen. certbot holt dann automatisch ein echtes Zertifikat.

**Prüfen:**
```bash
docker compose ps
curl https://spri.example.de/health            # {"status":"UP"}
curl https://spri.example.de/api/v1/about
docker compose logs proxy certbot              # Zertifikatsstatus
```

**Lokal testen ohne öffentlichen DNS-Namen:** `PUBLIC_HOSTNAME=localhost` lassen. certbot wird dann übersprungen, und der Proxy bleibt beim selbst signierten Zertifikat (`curl -k https://localhost/health`).

## Variante B: vorhandener Reverse-Proxy

Wenn auf dem Server bereits ein nginx mit Let's Encrypt die Ports 80/443 belegt:

```bash
# in .env: COMPOSE_PROFILES= (leer)
docker compose -f compose.yaml -f compose.external-proxy.yaml up -d --build
```

`api` ist dann unter `127.0.0.1:8080` erreichbar, `frontend` unter `127.0.0.1:8081`. Für den vorhandenen nginx:
1. `deploy/nginx/snippets/*` nach `/etc/nginx/snippets/` kopieren.
2. `deploy/nginx/external/openspri.conf` als Vorlage nehmen (Hostname und Zertifikatspfade anpassen).
3. **Zertifikatsschlüssel prüfen.** Der AK verlangt ECDSA P-256 oder RSA ≥ 3072 Bit. RSA 2048 ist für neue Zertifikate unzulässig:
   ```bash
   openssl x509 -in /etc/letsencrypt/live/<host>/cert.pem -noout -text | grep "Public-Key"
   # bei RSA 2048: certbot renew --cert-name <host> --key-type ecdsa --force-renewal
   ```

## Konfiguration (`.env`)

| Variable | Bedeutung | Standard |
|---|---|---|
| `COMPOSE_PROFILES` | `proxy` = eingebauter nginx + certbot, leer = externer Proxy | `proxy` |
| `DB_PASSWORD`, `DB_ROOT_PASSWORD` | Datenbankpasswörter (Pflicht) | – |
| `PUBLIC_HOSTNAME` | öffentlicher DNS-Name des S/PRI-Endpunkts | `localhost` |
| `ACME_EMAIL` | E-Mail für das Let's-Encrypt-Konto (optional) | leer |
| `ACME_STAGING` | Testzertifikate von Let's Encrypt | `true` |
| `ACME_ENABLED` | `false` = kein Let's Encrypt (nur für Tests) | `true` |
| `ACME_KEY_TYPE` | `ecdsa` (P-256) oder `rsa` (3072) | `ecdsa` |
| `HTTP_PORT`, `HTTPS_PORT` | Ports des Proxys auf dem Host | `80`, `443` |
| `OPENSPRI_BUILD_DIGEST` | Image-Digest, an dem zertifizierte Builds erkannt werden | leer |

## Signaturschlüssel (WS-Security)

Jede S/PRI-Nachricht wird signiert (AK-Sicherheitsdokument V3.0, Kap. 2.5): X.509 v3, SHA256withRSA, **RSA ≥ 3072 Bit**. Selbstsignierte Zertifikate sind erlaubt. Der AK empfiehlt einen **eigenen** Schlüssel, getrennt vom TLS-Zertifikat.

```bash
mkdir -p secrets
keytool -genkeypair -alias spri-signature -keyalg RSA -keysize 3072 -sigalg SHA256withRSA \
  -validity 3650 -dname "CN=S/PRI Signatur, O=Beispiel GmbH, C=DE" \
  -storetype PKCS12 -keystore secrets/spri-signature.p12
# Nur der Container-Benutzer (UID 10001) darf den Schlüssel lesen:
sudo chown 10001:10001 secrets/spri-signature.p12
sudo chmod 600 secrets/spri-signature.p12
```

Wichtig:
- Die Datei gehört nach **`./secrets/`** im Projektverzeichnis. `/run/openspri/…` ist der Pfad **im Container**. Compose bindet `./secrets` dorthin ein. Auf dem Host liegt `/run` außerdem im RAM und ist nach einem Neustart leer.
- Für `-dname` den echten Firmennamen verwenden. Partner sehen ihn im Zertifikat.
- Den Keystore sichern, aber nie ins Repository einchecken (`secrets/` ist in `.gitignore`). Bei Verlust muss allen Partnern ein neues Zertifikat übermittelt werden.

In `.env` (Pfad im Container):
```
OPENSPRI_SOAP_SIGNING_KEYSTORE=/run/openspri/spri-signature.p12
OPENSPRI_SOAP_SIGNING_KEYSTORE_PASSWORD=<Passwort>
```

Ohne konfigurierten Schlüssel erzeugt open-spri beim Start einen **vorläufigen** Schlüssel und warnt im Log. Partner können unsere Signaturen dann nach jedem Neustart nicht mehr prüfen. Das ist nur für die Entwicklung gedacht. Ab M4 erzeugt und speichert der Setup-Assistent den Schlüssel.

Das Zertifikat (`keytool -exportcert -rfc -alias spri-signature -keystore secrets/spri-signature.p12`) geht an jeden Partner, zusammen mit unserer Outbound-IP und der Endpunkt-Adresse.

## S/PRI-Endpunkt

- Adresse: `https://<PUBLIC_HOSTNAME>/spri/v4`, WSDL unter `…/spri/v4?wsdl` (Service `SpriService`, Operationen `annehmenAuftrag` und `annehmenMeldung`)
- Nur freigeschaltete Partner-IPs erreichen den Endpunkt, alle anderen erhalten HTTP 403. Partner werden ab M4 über REST und Oberfläche angelegt.
- Jede Anfrage wird mit einer signierten TEQ beantwortet:

  | Prüfung | TEQ |
  |---|---|
  | alles in Ordnung | `OK` |
  | kein SOAP 1.1 bzw. kein gültiges XML, XSD-Verstoß | `0995` |
  | Signatur fehlt oder ist ungültig, fremdes Zertifikat, signaturId passt nicht | `0999` |
  | Major-Release ≠ 4 oder Versionsfelder fehlerhaft | `0993` |
  | Speichern fehlgeschlagen | `0999` (der Partner sendet erneut) |

- Jede ein- und ausgehende Nachricht wird unverändert archiviert (Tabelle `soap_archive`, inkl. SHA-256 der Anfrage). Der Grund einer Ablehnung steht nur intern im Archiv und geht nie an den Partner.

## Zusätzliche IP-Sperre für den S/PRI-Endpunkt

open-spri prüft die IPs der Partner selbst. Zusätzlich lässt sich in nginx eine zweite Sperre setzen: `deploy/nginx/snippets/openspri-spri-allowlist.conf` bearbeiten, dann `docker compose restart proxy`.

## Endpunkte

| Pfad | Zweck | Zugriff |
|---|---|---|
| `/spri/v4` | S/PRI-SOAP-Endpunkt, WSDL unter `?wsdl` | Partner, IP-beschränkt |
| `/replikat/` | Replikat-Download (ab M6) | Partner |
| `/api/v1/…` | REST-API | Nutzer, Fremdsysteme |
| `/api/docs` | Swagger UI | Nutzer |
| `/health` | Health-Check | Monitoring |
| `/` | Web-Oberfläche | Nutzer |
