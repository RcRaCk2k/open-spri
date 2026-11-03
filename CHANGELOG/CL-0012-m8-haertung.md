# CL-0012: M8 – Härtung und Release-Kandidat

| | |
|---|---|
| Datum | 2026-11-02 |
| Meilenstein | M8 (in Arbeit) |
| Bezug | AUFGABE 6 (NF-01, NF-02, NF-08 bis NF-11, NF-13), 7.3, 7.4, 7.6, 8.1, 10.1 Punkt 7 und 8, 12.1; ADR-0010 |

## Motivation
M8 macht open-spri betriebsfertig. Dazu gehören Überwachung, Begrenzung der Angriffsfläche, Datenschutz, Last- und Sicherheitstests, eine nachvollziehbare Lieferkette (SBOM, signierte Images) und eine vollständige Betriebs- und Partnerdoku. Am Ende steht der Tag `v1.0.0-rc1`.

## Änderungen

### Teil 1: Überwachung, Ratenbegrenzung, Security-Header, Aufbewahrung
- **Metriken (NF-11):**
  - `GET /metrics` im Prometheus-Format, nur mit dem neuen Scope `metrics:read`; nginx leitet `/metrics` weiter
  - `openspri_inbound_requests_seconds{partner,operation,teq}` misst die Zeit vom Eingang bis zur TEQ als Histogramm mit den Schwellen 1 s, 10 s und 30 s (NF-01). Abgewiesene Adressen zählen als `teq="forbidden"`.
  - `openspri_outbound_attempts_seconds{partner,operation,result,teq}` erfasst Sendeversuche mit Ergebnis (Fehlerrate je Partner).
  - `openspri_outbox_messages{status}` und `openspri_inbox_messages{status}` zeigen die Warteschlangentiefe; die Zählung ist 5 s gepuffert.
  - Port-Erweiterung: `DeliveryQueries.countByStatus()` und `InboxQueries.countByStatus()`. Der SOAP-Adapter meldet über die Schnittstelle `SpriMetrics`, die Micrometer-Umsetzung liegt in der Konfiguration.
- **Logs (NF-11):** Im Container schreibt `api` JSON-Zeilen im Elastic Common Schema (`OPENSPRI_LOG_FORMAT=ecs`, leer für Klartext). Das Setup-Token steht jetzt in einer Zeile (`setup token: …`) und lässt sich in beiden Formaten mit `grep -o` finden. CI, Doku und die Setup-Seite sind angepasst.
- **Ratenbegrenzung (AUFGABE 7.3):**
  - Token-Bucket je API-Token (öffentlicher Präfix), sonst je Session, sonst je Adresse: 1200 Anfragen pro Minute
  - Anmeldung und Setup: 20 Versuche pro Minute je Adresse
  - Darüber gibt es 429 mit `Retry-After`. Der Filter läuft vor Spring Security, eine Flut kostet also kein Passwort-Hashing.
  - Der S/PRI-Endpunkt ist ausgenommen.
- **Security-Header:**
  - Das Frontend-nginx sendet Content-Security-Policy (`script-src 'self'`, `frame-ancestors 'none'` …), `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy` und `Cross-Origin-Opener-Policy`.
  - nginx verrät seine Version nicht mehr (`server_tokens off`).
  - Die API sendet die Standard-Header von Spring Security; ein Test sichert sie ab.
- **Aufbewahrung von Endkundendaten (NF-10, ADR-0010):**
  - `OPENSPRI_RETENTION_PERSONAL_DATA` (Standard `0` = aus)
  - Ein täglicher Lauf entfernt nach der Frist die Inhalte abgeschlossener Vorgänge aus Archiv, Eingang, Postausgang und Entwürfen sowie den Adressschlüssel (R-10).
  - Metadaten, TEQ-Antwort und SHA-256 der Rohnachricht bleiben erhalten.
  - `GET /messages/{id}` zeigt `requestSha256` und `contentPurgedAt`, `/raw` antwortet danach mit 410.

### Teil 2: Last- und Sicherheitstests, Lieferkette
- **TEQ auch für unlesbare Anfragen:**
  - Der neue Filter `SpriRequestFilter` vor CXF schließt eine Lücke. Bisher endeten Anfragen mit DTD, kaputtem XML oder unbekannter SOAPAction schon im CXF-Parser mit einem SOAP-Fault (HTTP 500), ohne Archiv.
  - Jetzt beantwortet die Eingangsprüfung sie mit TEQ 0995 und archiviert sie (Spezifikation 11.2.3.1, NF-09, ADR-0005).
  - Anfragen über `OPENSPRI_SOAP_MAX_REQUEST_BYTES` (30 MB) werden nicht weiter gelesen. Die ersten 64 KB kommen ins Archiv, die Antwort ist 0995.
  - Bei falscher SOAPAction ergibt sich die Operation aus dem Inhalt.
- **Lasttest (AUFGABE 10.1 Punkt 7):** `LoadTest` (`./gradlew :app:test -Pload`) läuft mit MariaDB über echtes HTTP:
  - 1000 signierte Aufträge von 50 parallelen Sendern; Prüfung TEQ p95 < 1 s
  - ein Auftrag mit 10 MB Anlage; Prüfung < 120 s
  - Der Test erzwingt die Pflichtgrenze der Spezifikation (p95 ≤ 10 s). Das interne Ziel p95 < 1 s wird gemessen und in der CI als Warnung gemeldet, wenn es verfehlt wird. Auf geteilten Runnern schwankt es zu stark für eine harte Grenze.
  - Messwerte: p50 stabil bei etwa 200 ms; p95 schwankt bei gleichem Code zwischen 0,45 und 1,7 s (lokal) bzw. 0,9 s (CI); die 10-MB-Anlage in 0,7 bis 0,9 s. Die Ausreißer passen zu Wartezeiten auf den Verbindungspool (10 Verbindungen bei 50 parallelen Sendern); offen für eine spätere Optimierung.
- **Sicherheitstests (AUFGABE 10.1 Punkt 8):**
  - `SoapHardeningTest`: XXE, Entity-Expansion („billion laughs“), Größenlimit, kaputtes XML
  - CI: testssl.sh gegen den Proxy. Alles ab MEDIUM außer Befunden zum Testzertifikat lässt den Build scheitern.
  - CI: OWASP-ZAP-Baseline gegen den Stack, akzeptierte Warnungen mit Begründung in `.github/zap/rules.tsv`
  - Das Frontend sendet zusätzlich `Cross-Origin-Embedder-Policy` und `Cross-Origin-Resource-Policy` (ZAP 90004).
- **Abhängigkeiten (Trivy, AUFGABE 13):**
  - Spring Boot 4.1.2 bringt Tomcat 11.0.27 und Jackson 3.1.7.
  - Jackson 2 ist auf 2.22.3 und Guava auf 33.7.2 festgelegt. Beide kommen transitiv über CXF bzw. WSS4J.
  - Damit bleiben keine HIGH/CRITICAL-Befunde mit verfügbarem Fix.
  - Die CI scannt die Images `api`, `frontend` und `proxy`. Akzeptierte Befunde stünden in `.trivyignore`, derzeit keine.
- **Lieferkette (NF-13):**
  - Release-Workflow für Tags `v*`: Images für amd64 und arm64 nach GHCR, Provenance-Attestierung
  - Cosign-Signatur (keyless) und CycloneDX-SBOM als signierte Attestierung
  - GitHub-Release mit SBOMs und Digests
  - Das Jar ist reproduzierbar (ohne Build-Zeit, feste Reihenfolge); die Image-Zeitstempel kommen aus dem Commit.
- **Simulator:** Der REST-Client des Simulators wartet bei 429 die Zeit aus `Retry-After` ab und wiederholt.

### Teil 3: Selbsttests, Egress je Partner, Betriebs- und Partnerdoku
- **Selbsttests (AUFGABE 7.4, 7.6):**
  - `GET /api/v1/selftest/tls` ruft den eigenen öffentlichen Endpunkt wie ein Partner auf. Geprüft werden Protokoll, Vertrauenskette (nicht selbstsigniert), Hostname, Schlüssel (ECC ≥ 256 / RSA ≥ 3072), Signaturalgorithmus, Aussteller gegen `OPENSPRI_SELFTEST_TRUSTED_ISSUERS` und Restlaufzeit (Warnung unter 14 Tagen).
  - `GET /api/v1/selftest/outbound-ip` vergleicht die tatsächliche Absenderadresse mit den eingetragenen Outbound-Adressen. Der Echo-Dienst (`OPENSPRI_SELFTEST_IP_ECHO_URL`) ist nicht vorbelegt; ohne ihn antwortet der Endpunkt mit 409.
  - In der Oberfläche unter *Einstellungen → Selbsttests*.
- **Egress-Proxy je Partner abschaltbar (AUFGABE 7.6):** neues Feld `useOutboundProxy` (Standard `true`), Migration V16, Schalter auf der Partnerseite. `SpriClient` nutzt `SPRI_OUTBOUND_PROXY` nur für Partner mit eingeschaltetem Schalter.
- **Deploy:**
  - `deploy/egress/` (SNAT: Compose-Netz mit fester Bridge und iptables-Skript)
  - `deploy/backup/backup.sh` (`mariadb-dump --single-transaction` plus `.env`, `secrets/`, `config/`, Rotation). Dump und Restore wurden an MariaDB 11.8 erprobt.
- **Doku:**
  - `docs/betrieb.md` ist vollständig, mit den neuen Kapiteln Ausgehende Adresse, Selbsttests, Backup und Restore, Updates, Zertifikatswechsel, Verfügbarkeit und Wartung.
  - neu: `docs/partner-onboarding.md` (Datenaustausch, Anlage, Verbindungstest mit Fehlerbildern, Checkliste, laufender Betrieb)
  - README mit Status Release-Kandidat und Betriebsübersicht; API-Doku zu Selbsttests und `useOutboundProxy`

## Auswirkungen
- S/PRI-Schnittstelle: Unlesbare oder zu große Anfragen erhalten TEQ 0995 statt eines SOAP-Faults und werden archiviert.
- REST-API: `GET /api/v1/selftest/tls` und `/outbound-ip`, Partnerfeld `useOutboundProxy`, neuer Scope `metrics:read`, `GET /metrics`, Status 429 (Ratenbegrenzung) und 410 (gelöschter Inhalt), neue Felder `requestSha256` und `contentPurgedAt` im Archiv
- Datenbank (Migration): V16 (`partner.use_outbound_proxy`), V15, mit `content_purged_at` in `soap_archive`, `inbox_message` und `outbox_message` sowie Indizes auf den Zeitspalten
- Konfiguration: `OPENSPRI_SELFTEST_TRUSTED_ISSUERS`, `OPENSPRI_SELFTEST_IP_ECHO_URL`, `OPENSPRI_LOG_FORMAT`, `OPENSPRI_RATE_LIMIT_REQUESTS_PER_MINUTE`, `OPENSPRI_RATE_LIMIT_LOGIN_PER_MINUTE`, `OPENSPRI_RETENTION_PERSONAL_DATA`, `OPENSPRI_RETENTION_CRON`
- Betrieb/Sicherheit: `docs/betrieb.md` mit den neuen Abschnitten „Überwachung“ (Prometheus-Konfiguration, Alarmregeln, Logs) und „Datenschutz und Aufbewahrung“

## Tests
- `RateLimitTest`: Token-Bucket, `Retry-After`, Begrenzung der Anmeldung je Adresse, Begrenzung der API je Token
- `SecurityTest`:
  - **Auth-Bypass:** Jede Operation der OpenAPI-Datei (über 80) antwortet ohne Anmeldung mit 401 oder 403 (AUFGABE 10.1 Punkt 8).
  - `/metrics` nur mit `metrics:read`
  - Security-Header der API
- `SoapEndToEndTest`: TEQ-Latenz je Partner und abgewiesene Adressen erscheinen in den Metriken.
- `RetentionServiceTest` und `ApplicationIntegrationTest` (MariaDB): Nur abgeschlossene Vorgänge vor dem Stichtag verlieren ihren Inhalt; offene und kürzlich abgeschlossene bleiben unverändert. Hash und TEQ bleiben erhalten, ein zweiter Lauf findet nichts mehr.
- `SoapHardeningTest`, `LoadTest` (Teil 2, siehe oben)
- `SelfTestsTest`: echter TLS-Handshake gegen einen lokalen Server mit selbstsigniertem RSA-2048-Zertifikat, Warnung vor Ablauf, Whitelist, Abgleich der Outbound-IP, lokaler Echo-Dienst
- `PartnerApiTest`: Schalter `useOutboundProxy`, Selbsttests (Scope, unerreichbare Adresse, 409 ohne Echo-Dienst)
- Playwright: Selbsttest-Tab mit axe; lokal 15/15
- Playwright: Die Suite prüft zusätzlich, dass keine Seite die Content-Security-Policy verletzt. Lokal lief sie gegen den gebauten Frontend-nginx (15/15).
