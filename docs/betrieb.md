# Betrieb von open-spri

Dieses Handbuch beschreibt Installation, Einrichtung und Betrieb, Stand Release-Kandidat 1.0. Wie ein neuer Partner angebunden wird, steht in [partner-onboarding.md](partner-onboarding.md).

**Inhalt:** [Voraussetzungen](#voraussetzungen) · [Installation](#variante-a-all-in-one-empfohlen) · [Ausgehende Adresse](#ausgehende-adresse-outbound-ip) · [Konfiguration](#konfiguration-env) · [Ersteinrichtung](#ersteinrichtung) · [Signaturschlüssel](#signaturschlüssel-ws-security) · [Selbsttests](#selbsttests) · [Überwachung](#überwachung) · [Backup und Restore](#backup-und-restore) · [Updates](#updates) · [Zertifikatswechsel](#zertifikatswechsel) · [Verfügbarkeit und Wartung](#verfügbarkeit-und-wartung) · [Datenschutz](#datenschutz-und-aufbewahrung)

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

## Ausgehende Adresse (Outbound-IP)

Partner lassen nur freigeschaltete Absenderadressen an ihren Endpunkt (AK-Sicherheitsdokument 2.6). Alle Verbindungen von `api` zu Partnern müssen deshalb über eine **feste öffentliche IPv4** laufen, die Outbound-IP. Sie darf sich von der Inbound-IP unterscheiden (AUFGABE 7.6). Es gibt zwei Wege:

1. **SNAT auf dem Host (Standard):** Der Stack bekommt ein eigenes Docker-Netz, und eine iptables-Regel setzt die Quelladresse.
   ```bash
   docker compose -f compose.yaml -f deploy/egress/compose.snat.yaml up -d
   sudo OUTBOUND_IP=203.0.113.10 deploy/egress/snat.sh     # nach jedem Neustart, z. B. als systemd-Unit
   ```
   Die Outbound-IP muss auf einer Schnittstelle des Hosts liegen. Hat der Host nur eine öffentliche Adresse, ist sie ohnehin die Outbound-IP, und SNAT ist nicht nötig.
2. **Egress-Proxy:** `SPRI_OUTBOUND_PROXY=http://proxy.example.de:3128`, etwa ein Firmen-Proxy oder Squid mit fester Adresse. Der Proxy muss `CONNECT` auf Port 443 erlauben. Je Partner lässt er sich abschalten (Partnerseite, Schalter „Über den Egress-Proxy senden“, bzw. `useOutboundProxy`). Der Proxy gilt für S/PRI-Nachrichten. Replikat-Downloads und Online-Abfragen bei Partnern laufen über das Routing des Hosts, also über SNAT.

**Mehrere Uplinks (ECMP, BGP):** Dockers Masquerading setzt als Absender die Adresse der Schnittstelle, über die ein Paket den Host verlässt. Das `src` der Route beachtet es nicht. Verteilt der Host seinen Verkehr auf mehrere Uplinks, etwa per ECMP oder mit BIRD und einer Dienstadresse auf `lo`, wechselt die Absenderadresse der Container von Verbindung zu Verbindung. Partner, die nur eine Adresse freigeschaltet haben, lehnen dann einen Teil der Nachrichten ab. Prüfen lässt sich das mit mehreren Aufrufen aus dem Container:
```bash
for i in 1 2 3 4 5 6; do docker compose exec -T api bash -c 'exec 3<>/dev/tcp/api6.ipify.org/80 && printf "GET / HTTP/1.0\r\nHost: api6.ipify.org\r\n\r\n" >&3 && tail -1 <&3'; echo; done | sort | uniq -c
```
Erscheint mehr als eine Adresse, hilft eine eigene SNAT-Regel auf die feste Dienstadresse. Mit nftables (Debian 13) sieht das so aus, als `/etc/nftables.d/openspri-snat.nft`:
```
table inet openspri_snat
delete table inet openspri_snat
table inet openspri_snat {
	chain postrouting {
		# vor Dockers MASQUERADE (Priorität srcnat = 100)
		type nat hook postrouting priority srcnat - 10; policy accept;
		iifname { "docker0", "br-*" } oifname { "ens18", "ens19" } meta nfproto ipv4 snat ip to 203.0.113.10
		iifname { "docker0", "br-*" } oifname { "ens18", "ens19" } meta nfproto ipv6 snat ip6 to 2001:db8::10
	}
}
```
Schnittstellennamen und Adressen sind anzupassen. Die Regel hängt nicht vom Subnetz des Docker-Netzes ab und gilt deshalb auch, nachdem das Netz neu angelegt wurde. Aktiviert wird sie mit `include "/etc/nftables.d/*.nft"` in `/etc/nftables.conf` und `systemctl enable --now nftables`.

**Achtung, `flush ruleset`:** Debians Vorgabe für `/etc/nftables.conf` beginnt mit `flush ruleset`. Ein `systemctl reload nftables` oder `nft -f /etc/nftables.conf` löscht dann auch alle Regeln, die Docker angelegt hat. Danach sind die veröffentlichten Ports und der Ausgang der Container weg, bis Docker neu startet. Die Zeile deshalb entfernen und eigene Tabellen wie oben mit `table …` / `delete table …` idempotent laden.

**IPv6:** Damit open-spri bei Partnern, die per IPv6 kommen, die echte Absenderadresse sieht, braucht das Netz des Stacks IPv6. Sonst nimmt Dockers Userland-Proxy die Verbindung an, und open-spri sieht nur eine interne Adresse. Die IP-Freigabe je Partner greift dann nicht. Ab Docker 27 genügt dafür eine `compose.override.yaml` neben `compose.yaml`:
```yaml
networks:
  default:
    enable_ipv6: true     # Docker vergibt ein ULA-Präfix und setzt NAT für den Ausgang
```
Danach `docker compose down && docker compose up -d`, weil das Netz neu angelegt werden muss. Docker setzt dabei `net.ipv6.conf.all.forwarding=1`. Bezieht der Host seine IPv6-Adresse per Router Advertisement, sollte in systemd-networkd `IPv6AcceptRA=yes` ausdrücklich gesetzt sein. Sonst kann der Host mit eingeschaltetem Forwarding keine Router Advertisements mehr annehmen und dadurch SLAAC-Adresse und Standardroute verlieren.

Die Outbound-IP(s) gehören in die Einstellungen (*Outbound-Adressen*). Sie erscheinen auf dem Onboarding-Blatt für Partner. Ob die Adresse stimmt, zeigt der [Selbsttest](#selbsttests). Ändert sie sich, rechtzeitig alle Partner informieren (*Zustellmonitor → Wartung ankündigen*), denn sonst lehnen sie unsere Nachrichten ab.

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
| `SPRI_OUTBOUND_PROXY` | Egress-Proxy für S/PRI-Nachrichten, z. B. `http://proxy:3128` | leer |
| `OPENSPRI_SELFTEST_TRUSTED_ISSUERS` | Teile des Aussteller-DN der CAs auf der AK-Whitelist, kommagetrennt, z. B. `Let's Encrypt` | leer (nicht geprüft) |
| `OPENSPRI_SELFTEST_IP_ECHO_URL` | Dienst, der die IP des Aufrufers als Text zurückgibt, für den Outbound-IP-Selbsttest | leer (Test aus) |
| `OPENSPRI_RETENTION_PERSONAL_DATA` | Endkundendaten so lange nach Abschluss aufbewahren, z. B. `730d`; `0` = unbegrenzt ([Datenschutz](#datenschutz-und-aufbewahrung)) | `0` |
| `OPENSPRI_LOG_FORMAT` | `ecs` = JSON-Zeilen, leer = Klartext | `ecs` |
| `OPENSPRI_RATE_LIMIT_REQUESTS_PER_MINUTE` | REST-Anfragen je Token, Session oder Adresse und Minute | `1200` |
| `OPENSPRI_RATE_LIMIT_LOGIN_PER_MINUTE` | Anmelde- und Setup-Versuche je Adresse und Minute | `20` |

## Ersteinrichtung

1. **Master-Schlüssel** einmalig erzeugen und in `.env` eintragen: `OPENSPRI_MASTER_KEY=$(openssl rand -base64 32)`. Mit ihm wird der Signaturschlüssel verschlüsselt, den der Setup-Assistent in der Datenbank ablegt (AES-256-GCM). Der Master-Schlüssel selbst liegt nie in der Datenbank. **Sichert ihn**, denn ohne ihn ist der gespeicherte Schlüssel unbrauchbar.
2. `docker compose up -d` ausführen und im Log das **Setup-Token** suchen: `docker compose logs api | grep -o "setup token: [A-Za-z0-9_-]*"`.
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

## Selbsttests

Unter *Einstellungen → Selbsttests* (REST: `GET /api/v1/selftest/tls` und `/outbound-ip`, Scope `settings:admin`, siehe [api/setup.md](api/setup.md#selbsttests-settingsadmin)):

- **TLS:** Ruft den eingetragenen öffentlichen S/PRI-Endpunkt so auf wie ein Partner. Geprüft werden TLS ≥ 1.2, die Vertrauenskette (öffentliche CA, nicht selbstsigniert), der Hostname, der Schlüssel (ECC ≥ 256 oder RSA ≥ 3072 Bit), der Signaturalgorithmus, der Aussteller gegen `OPENSPRI_SELFTEST_TRUSTED_ISSUERS` und die Restlaufzeit (Warnung unter 14 Tagen). Den Test nach der Inbetriebnahme und nach jedem Zertifikatswechsel ausführen. Solange das Übergangszertifikat des Proxys aktiv ist, schlägt er fehl.
- **Outbound-IP:** Fragt `OPENSPRI_SELFTEST_IP_ECHO_URL`, von welcher Adresse unsere Anfragen ankommen, und vergleicht sie mit den eingetragenen Outbound-Adressen. Ohne diese Variable ist der Test aus. open-spri ruft von sich aus keinen fremden Dienst auf. Geeignet ist ein eigener Dienst oder ein öffentlicher wie `https://api.ipify.org`.

Eine gründlichere Prüfung der TLS-Konfiguration, wie sie die CI gegen den Proxy macht:
```bash
docker run --rm ghcr.io/testssl/testssl.sh:3.2 --protocols --std --fs --vulnerable --headers https://spri.example.de
```

## Überwachung

**Health:** `/health` (gesamt), `/health/liveness`, `/health/readiness`. Der Compose-Healthcheck nutzt `readiness`. Die Antwort enthält bewusst keine Details.

**Metriken:** `GET /metrics` im Prometheus-Format, nur mit einem API-Token mit Scope `metrics:read`. Ein solches Token legt ein Admin unter *Benutzer und Tokens* an (Rolle `ADMIN`, Scopes nur `metrics:read`).

```yaml
# prometheus.yml
scrape_configs:
  - job_name: open-spri
    scheme: https
    metrics_path: /metrics
    authorization: { credentials_file: /etc/prometheus/openspri-token }
    static_configs: [{ targets: ["spri.example.de"] }]
```

| Metrik | Bedeutung |
|---|---|
| `openspri_inbound_requests_seconds{partner,operation,teq}` | Zeit vom Eingang bis zur TEQ, als Histogramm. `teq="forbidden"` heißt: Absender-IP nicht freigeschaltet. |
| `openspri_outbound_attempts_seconds{partner,operation,result,teq}` | Sendeversuche mit Ergebnis `acknowledged`, `no_answer`, `not_reached` oder `invalid` |
| `openspri_outbox_messages{status}` | Postausgang je Status |
| `openspri_inbox_messages{status}` | Eingang je Status |
| `http_server_requests_seconds`, `jvm_*`, `hikaricp_*` | Standardmetriken von Spring Boot (REST, JVM, Datenbankpool) |

`partner` ist die Partner-ID aus `GET /api/v1/partners`.

**Empfohlene Alarme:**

```yaml
groups:
  - name: open-spri
    rules:
      - alert: SpriTeqLatenz        # NF-01: p95 < 10 s Pflicht, intern < 1 s
        expr: histogram_quantile(0.95, sum by (le) (rate(openspri_inbound_requests_seconds_bucket[10m]))) > 1
        for: 10m
      - alert: SpriZustellungGestoert   # Partner nicht erreichbar oder Meldungen nicht zustellbar
        expr: openspri_outbox_messages{status=~"UNDELIVERABLE|UNCLEAR"} > 0
        for: 5m
      - alert: SpriEingangFehler    # Verarbeitung hängt; folgende Nachrichten desselben Auftrags warten
        expr: openspri_inbox_messages{status="FAILED"} > 0
        for: 5m
      - alert: SpriPartnerLehntAb   # viele negative TEQ eines Partners
        expr: sum by (partner) (rate(openspri_outbound_attempts_seconds_count{result="acknowledged",teq!="OK"}[1h])) > 0.01
```

**Logs:** Der Container `api` schreibt JSON-Zeilen im Elastic Common Schema (ECS) auf stdout (`OPENSPRI_LOG_FORMAT=ecs`). Sie lassen sich direkt von Loki, Elasticsearch oder Graylog einlesen. `docker compose logs api` zeigt sie unverändert. Klartext gibt es mit `OPENSPRI_LOG_FORMAT=` (leer). Die Logs enthalten keine Nachrichteninhalte und keine Geheimnisse. Endkundendaten stehen nur im Archiv der Datenbank.

**Ratenbegrenzung:** Die REST-API nimmt je Token, Session oder Adresse höchstens 1200 Anfragen pro Minute an, Anmeldung und Setup höchstens 20 je Adresse. Darüber antwortet sie mit 429 und `Retry-After`. Die Grenzen gelten je Instanz. Der S/PRI-Endpunkt ist nicht begrenzt, denn ein Partner muss immer eine TEQ bekommen.

## Backup und Restore

Zu sichern sind:
- die **Datenbank**
- **`.env`**, vor allem `OPENSPRI_MASTER_KEY`: Ohne ihn sind der gespeicherte Signaturschlüssel, die Webhook-Geheimnisse und die Zugangsdaten der Replikat-Quellen unbrauchbar.
- **`secrets/`** und **`config/`**

Das Skript `deploy/backup/backup.sh` erledigt das. Es erzeugt einen konsistenten Dump mit `mariadb-dump --single-transaction` im laufenden Betrieb, packt die Dateien dazu und löscht Sicherungen nach `KEEP_DAYS` (Standard 30):

```bash
deploy/backup/backup.sh /var/backups/open-spri
# crontab: 15 1 * * *  cd /opt/open-spri && deploy/backup/backup.sh /var/backups/open-spri
```

Die Sicherung enthält Geheimnisse und Endkundendaten. Legt sie verschlüsselt bzw. zugriffsgeschützt ab, möglichst auf einem anderen System, und lasst sie nicht länger liegen, als die [Aufbewahrungsfrist](#datenschutz-und-aufbewahrung) erlaubt.

**Restore** (gleiche oder neuere open-spri-Version):
```bash
docker compose down                                   # Stack anhalten
tar -xzf /var/backups/open-spri/<zeitpunkt>/files.tar.gz   # .env, secrets/, config/
docker compose up -d db                               # nur die Datenbank starten
gunzip -c /var/backups/open-spri/<zeitpunkt>/openspri.sql.gz \
  | docker compose exec -T db sh -c 'exec mariadb -uroot -p"$MARIADB_ROOT_PASSWORD"'
docker compose up -d                                  # api migriert bei Bedarf (Flyway)
```

Der Dump legt die Datenbank `openspri` neu an (`DROP`/`CREATE TABLE`). Nach dem Restore die Zustellung prüfen: Nachrichten, die nach der Sicherung gesendet wurden, kennt die Datenbank nicht. Eingehende Nachrichten aus dieser Zeit sind verloren, sofern Partner sie nicht erneut senden. Ein Restore ist deshalb mit den Partnern abzustimmen. Probt den Restore regelmäßig auf einem Testsystem.

## Updates

```bash
deploy/backup/backup.sh /var/backups/open-spri       # vorher sichern
git fetch --tags && git checkout v1.0.0               # gewünschte Version
docker compose up -d --build                          # oder fertige Images: OPENSPRI_VERSION=1.0.0 docker compose pull && docker compose up -d
docker compose ps; curl -fsS https://spri.example.de/health
```

- **Datenbank:** `api` migriert beim Start mit Flyway. Migrationen sind nur additiv bzw. rückwärtskompatibel, die vorige Version läuft also mit dem neuen Schema weiter. Ein Rückschritt auf eine ältere Version braucht trotzdem den Restore der Sicherung, wenn die neue Version Daten in einem neuen Format geschrieben hat.
- **Unterbrechung:** Ein Neustart von `api` dauert meist unter einer Minute. Partner wiederholen ihre Nachrichten laut Spezifikation (11.2.3.2), unsere eigenen warten im Postausgang. Für geplante Arbeiten gilt die Ankündigungsfrist (siehe [Verfügbarkeit und Wartung](#verfügbarkeit-und-wartung)).
- **Zertifizierte Builds:** Ein Update auf eine Version ohne Softwarezertifikat verlässt den zertifizierten Stand (`GET /api/v1/about` → `certifiedBuild`). Release-Notes und `CHANGELOG/` nennen Änderungen an der S/PRI-Schnittstelle.
- Vor dem Einsatz fertiger Images deren Signatur prüfen (nächster Abschnitt).

## Zertifikatswechsel

| Was | Wie | Partner informieren? |
|---|---|---|
| **TLS-Zertifikat** (All-in-One) | certbot verlängert automatisch alle 12 h, nginx lädt ohne Unterbrechung neu. Danach den [Selbsttest](#selbsttests) ausführen. | nein (kein Pinning) |
| TLS-Zertifikat (externer Proxy) | wie beim eigenen nginx üblich; ECDSA P-256 oder RSA ≥ 3072 | nein |
| **Eigener Signaturschlüssel** (WS-Security) | 1. Neues Zertifikat erzeugen oder übernehmen. 2. Das PEM allen Partnern mit Vorlauf zusenden; sie hinterlegen es zusätzlich zum alten. 3. Zum vereinbarten Zeitpunkt umstellen: `POST /api/v1/settings/signing-key` bzw. *Einstellungen → Signaturschlüssel*, wirkt sofort. | **ja**, mit Vorlauf; Vorlage über *Wartung ankündigen* |
| Signaturschlüssel per Keystore-Datei | neue Datei nach `secrets/`, Pfad und Passwort in `.env`, `docker compose up -d api` | ja, wie oben |
| **Zertifikat eines Partners** | neues zusätzlich hinterlegen; beide gelten, bis der Partner umgestellt hat; dann das alte entfernen ([api/partner.md](api/partner.md#zertifikatswechsel)) | – |

Abgelaufene Partnerzertifikate markiert die Partnerseite. Prüft sie regelmäßig.

## Verfügbarkeit und Wartung

Die Spezifikation (11.3) verlangt für NEU, KUE, AEN und PV Mo–Fr 6–24 Uhr, für die Entstörung (EST) **7×24**, mit 99,5 % im Jahresmittel (NF-02).

- **Ein Host:**
  - `restart: unless-stopped` und Healthchecks starten ausgefallene Container neu.
  - Die [Alarme](#überwachung) melden Störungen.
  - Mit einer nächtlichen Sicherung und einem geprobten Restore auf Ersatzhardware ist das für die meisten Betreiber ausreichend.
- **Mehrere `api`-Instanzen** dürfen gleichzeitig auf dieselbe Datenbank zugreifen. Postausgang und Eingang halten die Reihenfolge über Datenbanksperren ein (ADR-0007). Davor gehört ein Load-Balancer, der `/health/readiness` prüft. Die Ratenbegrenzung gilt dann je Instanz.
- **Datenbank:** Hochverfügbarkeit über MariaDB-Replikation (Primary/Replica mit manuellem oder automatischem Umschalten) oder Galera liegt beim Betreiber. open-spri braucht genau eine schreibbare Datenbank, an die alle Instanzen angebunden sind.
- **Wartung:** Arbeiten, die die Schnittstelle beeinträchtigen, sind allen Partnern **5 Werktage vorher** anzukündigen (11.5, NF-08), über *Zustellmonitor → Wartung ankündigen* bzw. `POST /api/v1/maintenance-announcements`. Dabei die 7×24-Pflicht für EST beachten.

## Images prüfen

Release-Images (`ghcr.io/rcrack2k/open-spri/{api,frontend,proxy}:<version>`) sind mit Cosign signiert, und zwar ohne Schlüssel über das GitHub-OIDC-Token des Release-Workflows. Jedes Image trägt eine CycloneDX-SBOM als signierte Attestierung. Prüfen vor dem Einsatz:

```bash
IMAGE=ghcr.io/rcrack2k/open-spri/api:1.0.0-rc1
cosign verify "$IMAGE" \
  --certificate-identity-regexp '^https://github.com/RcRaCk2k/open-spri/.github/workflows/release.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
cosign verify-attestation --type cyclonedx "$IMAGE" \
  --certificate-identity-regexp '^https://github.com/RcRaCk2k/open-spri/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com | jq -r .payload | base64 -d | jq '.predicate.components | length'
```

Die Digests jedes Releases stehen in den Release-Notes. Für einen zertifizierten Betrieb das Image per Digest einbinden und `OPENSPRI_BUILD_DIGEST` setzen (siehe README).

## Datenschutz und Aufbewahrung

Endkundendaten (Name, Anschrift, Kontakt, Eigentümer) stehen in den S/PRI-Nachrichten: im Archiv, im Eingang, im Postausgang und in Entwürfen (NF-10).

- **Zugriff:** Jeder Abruf einer Rohnachricht (`/messages/{id}/raw`) steht im Audit-Log (`archive.read`).
- **Aufbewahrung:** `OPENSPRI_RETENTION_PERSONAL_DATA` legt fest, wie lange nach Abschluss eines Auftrags die Inhalte erhalten bleiben. Ein täglicher Lauf (02:30 Uhr, `OPENSPRI_RETENTION_CRON`) löscht danach:
  - die Inhalte von Archiv, Eingang, Postausgang und Entwürfen
  - den Adressschlüssel abgeschlossener Aufträge

  Metadaten, TEQ und der SHA-256 jeder Rohnachricht bleiben als Nachweis erhalten (ADR-0010). Offene Aufträge sind nie betroffen.
- **Standard ist `0`, also keine Löschung.** Die richtige Frist hängt von euren Aufbewahrungspflichten ab, etwa aus Handels- und Steuerrecht oder aus Partnerverträgen. Legt sie bei der Inbetriebnahme mit eurem Datenschutzbeauftragten fest. Ein Beispiel ist `OPENSPRI_RETENTION_PERSONAL_DATA=730d`.
- **Backups** enthalten gelöschte Inhalte, bis sie rotiert sind. Die Rotation der Sicherungen sollte zur Frist passen.

## Endpunkte

| Pfad | Zweck | Zugriff |
|---|---|---|
| `/spri/v4` | S/PRI-SOAP-Endpunkt, WSDL unter `?wsdl` | Partner, IP-beschränkt |
| `/replikat/` | Replikat-Download (ab M6) | Partner |
| `/api/v1/…` | REST-API | Nutzer, Fremdsysteme |
| `/api/docs` | Swagger UI | Nutzer |
| `/health` | Health-Check | Monitoring |
| `/metrics` | Prometheus-Metriken | Monitoring, Token mit `metrics:read` |
| `/` | Web-Oberfläche | Nutzer |
