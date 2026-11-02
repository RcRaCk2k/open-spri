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

## Ersteinrichtung

1. **Master-Schlüssel** einmalig erzeugen und in `.env` eintragen: `OPENSPRI_MASTER_KEY=$(openssl rand -base64 32)`. Mit ihm wird der Signaturschlüssel verschlüsselt, den der Setup-Assistent in der Datenbank ablegt (AES-256-GCM). Der Master-Schlüssel selbst liegt nie in der Datenbank. **Sichert ihn**, denn ohne ihn ist der gespeicherte Schlüssel unbrauchbar.
2. `docker compose up -d` ausführen und im Log das **Setup-Token** suchen: `docker compose logs api | grep -A3 "setup token"`.
3. Die Web-Oberfläche öffnen und das Token eingeben. Danach erfasst der Assistent Admin-Benutzer, Firmendaten mit ITU Carrier Code (ICC), Rollen, Optionen und den Signaturschlüssel (neu erzeugen oder vorhandenen PKCS#12 übernehmen). Per REST geht das mit `POST /api/v1/setup` und dem Header `X-Setup-Token` (siehe `docs/api/`).
4. Nach dem Abschluss ist das Setup gesperrt. Änderungen laufen über die Einstellungen (`/api/v1/settings`).

## Signaturschlüssel (WS-Security)

Es gibt zwei Wege, der erste hat Vorrang:
- **Keystore-Datei** über `OPENSPRI_SOAP_SIGNING_KEYSTORE`, wie unten beschrieben. Damit liegt der Schlüssel außerhalb der Datenbank, er lässt sich dann aber nicht über die Oberfläche wechseln.
- **Gespeichert durch den Setup-Assistenten** (verschlüsselt mit dem Master-Schlüssel). Ein Wechsel über `POST /api/v1/settings/signing-key` wirkt sofort, ohne Neustart. Teilt das neue Zertifikat vorher allen Partnern mit.

Das Zertifikat für die Partner liefert `GET /api/v1/settings/signing-key` (Feld `certificatePem`).

### Keystore-Datei

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

## Meldungscodetabelle (Anlage 5)

Die Meldungscodes stellt der AK nur online in seinem Confluence bereit. Sie dürfen nicht mit open-spri ausgeliefert werden. So bindest du die Tabelle ein:

1. Die Tabelle unter <https://aksup.atlassian.net/wiki/spaces/SPE/pages/1221034635/SPRI+Meldungscodetabelle> als CSV exportieren.
2. Die Datei unverändert nach `./config/SPRI_Meldungscodetabelle.csv` legen. Das Format ist UTF-16, tabgetrennt, wie der Confluence-Export.
3. In `.env` eintragen: `OPENSPRI_MESSAGE_CODES_FILE=/etc/openspri/SPRI_Meldungscodetabelle.csv`
4. `docker compose up -d` ausführen. Beim Start importiert open-spri die Datei, sofern sie eine neue Version ist (erkennbar an der SHA-256-Prüfsumme), und aktiviert sie. Jede Version bleibt in der Datenbank erhalten (`message_code_table`).

Ohne Tabelle warnt open-spri im Log und kann Meldungscodes nicht prüfen (R-13). Codes, die die Testfallliste nutzt, die aber in der AK-Tabelle fehlen, ergänzt open-spri selbst und kennzeichnet sie (`docs/spec-abweichungen.md`, SA-001 und SA-008).

## Prüfung nach Anlage 1

open-spri prüft jede Nachricht auch gegen Anlage 1 und damit, welche Felder je Geschäftsfall und Produktgruppe Pflicht oder unzulässig sind (ADR-0006):

| Richtung | Standard | Wirkung |
|---|---|---|
| ausgehend (eigene Nachrichten) | `enforce` | Eine Nachricht mit Verstoß wird nicht gesendet. |
| eingehend (vom Partner) | `report` | Die Nachricht wird angenommen und der Verstoß im Archiv vermerkt (`soap_archive.findings`). |

Mit `OPENSPRI_SOAP_ATTRIBUTE_RULES_INBOUND=enforce` antwortet open-spri bei Verstößen mit TEQ 0995.

## Fachliche Verarbeitung

Angenommene Aufträge und Meldungen landen in der Warteschlange `inbox_message`. Ein Hintergrundprozess verarbeitet sie in der Reihenfolge des Eingangs, etwa jede Sekunde:
- Aufträge (wir sind Leistungserbringer) werden in `spri_order` angelegt.
- Meldungen bewegen den Auftrag weiter durch seinen Zustandsautomaten.

Was die Prüfungen finden, steht in `spri_order_history.findings` und `inbox_message.findings`. Das sind zum Beispiel Dubletten, R-05 (Termin mehr als 180 Tage in der Zukunft), R-10 (offener Auftrag am selben Anschluss), R-11 (keine gültige Vollmacht), R-12 (Projektkenner ohne Vereinbarung), unbekannter Rahmenvertrag oder unbekanntes Produkt (siehe [api/partner.md](api/partner.md#rahmenverträge-vollmachten-projektvereinbarungen)) und Meldungen außerhalb der Reihenfolge. Status `FAILED` bedeutet einen technischen Fehler, die Ursache steht in `inbox_message.error`.

Mehrere Instanzen dürfen parallel laufen. Je Auftrag wird streng in der Reihenfolge des Eingangs verarbeitet (ADR-0007). Ein Eintrag mit `FAILED` hält die folgenden Einträge desselben Auftrags an, bis er neu verarbeitet wurde.

## Postausgang und Zustellung

Alles, was open-spri an Partner sendet, läuft über den Postausgang `outbox_message` (ADR-0007):
- **Reihenfolge:** Je Auftrag bzw. Leitung geht eine Nachricht erst raus, wenn die vorige abgeschlossen ist.
- **Wiederholungen:** Wenn der Partner nicht erreichbar ist, wiederholt open-spri im Abstand von `OPENSPRI_OUTBOX_RETRY_INTERVAL` (Standard 5 Minuten), höchstens `OPENSPRI_OUTBOX_MAX_ATTEMPTS`-mal (Standard 12).
- **Status, die jemand ansehen muss:**

  | Status | Bedeutung | Was tun |
  |---|---|---|
  | `UNCLEAR` | eigener Auftrag ohne technische Quittung. Er könnte beim Partner angekommen sein. | Beim Partner nachfragen. Dann erneut senden **mit neuer Auftragsnummer** oder verwerfen (Spezifikation 11.2.3.1). |
  | `UNDELIVERABLE` | Wiederholungen erschöpft | erneut senden oder über den vereinbarten E-Mail-Kanal übermitteln (11.2.3.2) |
  | `DELIVERED_WITH_ERROR` | Meldung mit negativer TEQ. Sie gilt laut Spezifikation als zugestellt. | Fehlerliste prüfen und mit dem Partner klären |
  | `REJECTED` | eigener Auftrag mit negativer TEQ | Auftrag korrigieren und neu stellen |

  Abfrage und Eingriff über die REST-API: `GET /api/v1/deliveries?status=UNCLEAR`, `POST /api/v1/deliveries/{id}/retry`, `…/sent-by-email`, `…/cancel` (Scope `deliveries:write`, jeder Eingriff steht im Audit-Log). Ein Auftrag im Status `UNCLEAR` lässt sich **nicht** mit derselben Nummer erneut senden (NF-05): mit neuer Nummer neu stellen und den alten Eintrag zurückziehen. Der Eingang steht unter `GET /api/v1/inbox`; eine fehlgeschlagene Verarbeitung (`FAILED`) blockiert weitere Nachrichten desselben Auftrags, bis sie mit `POST /api/v1/inbox/{id}/reprocess` wiederholt wird.

**Voraussetzung für das Senden:** Die Meldungscodetabelle muss importiert sein. Ohne sie lehnt open-spri jede eigene Meldung ab, weil kein Meldungscode geprüft werden kann (R-13).

## Ereignisse und Webhooks

Fremdsysteme (CRM, Workflow) erfahren von neuen Aufträgen, Meldungen und Zustellproblemen auf zwei Wegen (D-14):

- **Event-Feed:** `GET /api/v1/events?after=<id>`. Der Client merkt sich die größte `id`. Ereignisse erscheinen mit etwa 2 s Verzögerung, damit beim Polling keines übersprungen wird.
- **Webhooks:** `POST /api/v1/webhooks` mit Ziel-URL und optional den gewünschten Typen. open-spri sendet jedes Ereignis einzeln als JSON und signiert es mit HMAC-SHA256 (Header `X-OpenSpri-Signature: t=…,v1=…`). Antwortet der Empfänger nicht mit 2xx, wiederholt open-spri **dasselbe** Ereignis mit wachsendem Abstand (10 s, verdoppelt, höchstens 1 h). Die folgenden Ereignisse warten so lange, damit die Reihenfolge erhalten bleibt. Das Geheimnis wird mit `OPENSPRI_MASTER_KEY` verschlüsselt gespeichert, Webhooks gibt es also nur mit Master-Key.

Ereignisse enthalten Nummern, Status und Meldungscodes, aber keine Endkundendaten. Sie werden nach `OPENSPRI_EVENTS_RETENTION` (Standard `90d`) gelöscht.

**Idempotenz:** Wer `POST /api/v1/orders…`, `/terminations` oder `/pv-requests/{id}/reply` mit dem Header `Idempotency-Key` aufruft, kann die Anfrage nach einem Netzfehler gefahrlos wiederholen: Eine Wiederholung mit demselben Schlüssel bekommt die gespeicherte Antwort, ohne dass etwas erneut gesendet wird. Die Schlüssel werden 24 h aufbewahrt.

## Benachrichtigungen per E-Mail

| Anlass | Empfänger |
|---|---|
| Schnittstelle eines Partners nicht erreichbar (höchstens einmal je Ausfall und Tag, Spezifikation 11.2.3.2) | technischer Kontakt des Partners und `OPENSPRI_NOTIFICATIONS_OPERATOR_EMAIL` |
| Nachricht nicht zustellbar | `OPENSPRI_NOTIFICATIONS_OPERATOR_EMAIL` |
| Wartung angekündigt (`POST /maintenance-announcements`, 5 Werktage Vorlauf nach Spezifikation 11.5) | technischer Kontakt jedes Partners und `OPENSPRI_NOTIFICATIONS_OPERATOR_EMAIL` |

Mailserver in `.env` eintragen:
```
SPRING_MAIL_HOST=smtp.example.de
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=open-spri@example.de
SPRING_MAIL_PASSWORD=...
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
OPENSPRI_NOTIFICATIONS_FROM=open-spri@example.de
OPENSPRI_NOTIFICATIONS_OPERATOR_EMAIL=betrieb@example.de
```
Ohne Mailserver schreibt open-spri die Benachrichtigungen nur ins Log.

## Replikat (Leistungserbringer)

Jeden Tag um 03:00 Uhr (`OPENSPRI_REPLIKAT_EXPORT_CRON`, Uhrzeit des Containers, `TZ=Europe/Berlin`) erzeugt open-spri für jeden freigegebenen Partner ein Voll- und ggf. ein Deltareplikat. Die Spezifikation verlangt, dass es bis 06:00 Uhr bereitliegt. Partner laden es unter `https://<host>/replikat/` mit ihrem Partner-Token, nginx leitet `/replikat/` an die API weiter. open-spri hält 31 Tage vor; die Dateien liegen in der Datenbank (`replikat_snapshot`). Schlägt ein Lauf fehl, steht das im Log. Details: [api/verfuegbarkeit.md](api/verfuegbarkeit.md).

## Replikat-Import (Auftraggeber)

Jeden Tag um 07:00 Uhr (`OPENSPRI_REPLIKAT_IMPORT_CRON`) importiert open-spri die Replikate aller aktiven Replikat-Quellen, mit MD5- und XSD-Prüfung. Die Zugangsdaten der Quellen sind mit `OPENSPRI_MASTER_KEY` verschlüsselt, ohne Master-Key lassen sich keine Quellen mit Zugangsdaten anlegen. Jeder Lauf steht unter `GET /api/v1/replikat/sources/{id}/imports`, ein Fehlschlag zusätzlich im Log. Der API-Container braucht dafür ausgehenden HTTPS-Zugang zu den Download-Servern der Partner.

## KFT-Simulator (nur Test und Entwicklung)

Mit `COMPOSE_PROFILES=proxy,simulator` startet zusätzlich der Dienst `spri-simulator`. Er spielt die Gegenseite der KFT-Testfälle gegen diese Instanz (Token mit `orders:override` in `OPENSPRI_SIM_TOKEN`, Testfallliste in `./config`). Bedienung und Bericht beschreibt [kft-szenarien.md](kft-szenarien.md#simulator-als-container). Der Simulator legt Testdaten an und gehört nie an eine produktive Instanz.

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
