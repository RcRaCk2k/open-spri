# open-spri

Freie, offene Implementierung der **S/PRI-Schnittstelle** (Supplier/Partner Requisition Interface, V4.4) des [Arbeitskreises Schnittstellen & Prozesse](https://ak-spri.de/spri/). Mit ihr bestellen Telekommunikationsanbieter Vorleistungsprodukte (FTTH, FTTB, FTTC) bei anderen Netzbetreibern und bieten ihre eigenen Anschlüsse über einen Standard an.

> **Status: Release-Kandidat 1.0 (M8). Als Nächstes folgt der Konformitätstest bei Enghouse (M9).** Alle 211 KFT-Testfälle laufen automatisiert gegen den eigenen Simulator, Stand in [`docs/kft-szenarien.md`](docs/kft-szenarien.md#stand).
> - Der S/PRI-Endpunkt nimmt signierte Aufträge und Meldungen an, prüft sie gegen XSD und Anlage 1 und quittiert sie mit TEQ.
> - Aufträge und Meldungen werden fachlich verarbeitet, mit Zustandsautomaten für alle Geschäftsfälle, abgeglichen mit den 211 Meldungsfolgen der KFT-Testfallliste.
> - Ausgehende Nachrichten laufen über einen Postausgang mit garantierter Reihenfolge, Wiederholungen und Ausfall-Mail, auch mit mehreren Instanzen.
> - REST-API mit Setup-Assistent, API-Tokens und Benutzern, Partnerverwaltung, Produktkatalog, Aufträgen und Meldungen in allen Rollen, Zustellmonitor, Nachrichtenarchiv, Audit-Log, Event-Feed, Webhooks und Idempotenz. Beschreibung mit curl-Beispielen in [`docs/api/`](docs/api/README.md).
> - Verfügbarkeit nach Replikat 1.7 ([`docs/api/verfuegbarkeit.md`](docs/api/verfuegbarkeit.md)): eigene Standorte und tägliches Replikat für Partner; Replikate der Partner importieren oder ihre Instanz online abfragen.
> - Web-Oberfläche ([`docs/frontend.md`](docs/frontend.md)):
>   - alle Seiten aus AUFGABE 8.6, vom Setup-Assistenten und dem Auftragsassistenten bis zu Zustellmonitor und Einstellungen
>   - nutzt nur die öffentliche REST-API, barrierearm nach WCAG 2.1 AA (axe in der E2E-Suite)
> - Betrieb und Härtung (M8):
>   - Prometheus-Metriken, JSON-Logs, Ratenbegrenzung, Security-Header
>   - Aufbewahrungsfrist für Endkundendaten
>   - Selbsttests für TLS und Outbound-IP
>   - Last- und Sicherheitstests in der CI (testssl.sh, OWASP ZAP, Trivy)
>   - signierte Images mit SBOM
>   - Betriebshandbuch und Anleitung zur Partneranbindung
> - Umfang und Fahrplan stehen in [`docs/AUFGABE-SPRI.md`](docs/AUFGABE-SPRI.md).

## Funktionsumfang (Ziel v1.0)

**S/PRI in beiden Rollen**
- **Auftraggeber:** Leitungen bei Partnern bestellen, ändern, kündigen und entstören. Als abgebender Provider Wechselanfragen (AKM-PV) beantworten.
- **Leistungserbringer:** Aufträge anderer Provider für eigene Leitungen annehmen, im Arbeitsvorrat bearbeiten und alle Meldungen zurücksenden (QEB, ABM, ERLM, ENTM, ABBM, VZM, TAM …)
- Geschäftsfälle: Bereitstellung (NEU), Kündigung (KUE-AG/KUE-LE), Leistungsmerkmaländerung (AEN-LMAE), Leistungsänderung (LAE), Providerwechsel (PV), Entstörung (EST), Diagnose (DIAG-GET/SET). Jeweils mit Storno, Terminverschiebung und Reklamation, wo der Geschäftsfall sie vorsieht.
- FTTH-Konnektivitätsauftrag (Ausbau), HomeID, Bestellung im Namen Dritter, Projekt- und Auftragskenner
- SOAP 1.1 mit WS-Security-Signatur, XSD-Validierung, Meldungsreihenfolge, Wiederholungen und Nachrichtenarchiv, wie von der Spezifikation gefordert

**Verfügbarkeit**
- Replikate nach AK-Spezifikation 1.7 erzeugen und bereitstellen (voll und Delta, täglich, 31 Tage)
- Replikate von Partnern importieren, Adresssuche über alle Partner
- Online-Verfügbarkeitsabfrage anbieten und nutzen

**Integration**
- Vollständig dokumentierte **REST-API**: Das Web-Frontend benutzt dieselben Aufrufe, die externe CRM- und Workflow-Systeme nutzen.
- Long-Life-API-Tokens. Weitere Anmeldeverfahren (OAuth2/OIDC, SAML) lassen sich als Module ergänzen.
- Webhooks und Event-Feed für Statusänderungen

**Betrieb**
- **All-in-One-Stack:** API, MariaDB, Frontend sowie nginx und certbot (Let's Encrypt mit automatischer Verlängerung). Nach `docker compose up` ist die Software per HTTPS erreichbar. Alternativ läuft sie hinter einem vorhandenen Reverse-Proxy (Konfiguration inklusive).
- Eingehende und ausgehende IP-Adressen sind getrennt konfigurierbar, mit Selbsttests für Zertifikat und Outbound-IP.
- Setup-Assistent beim ersten Start (Firmendaten, ITU Carrier Code, Rollen, Zertifikate)
- Eingebauter Simulator, der die Testfälle des Konformitätstests nachbildet

## Schnellstart

Voraussetzung: Docker mit Compose v2.

```bash
git clone https://github.com/RcRaCk2k/open-spri.git && cd open-spri
cp .env.example .env        # Passwörter setzen, PUBLIC_HOSTNAME und ACME_EMAIL eintragen
docker compose up -d --build
curl -k https://localhost/health
```

Der Stack besteht aus `api` (Kotlin/Spring Boot), `db` (MariaDB 11.8), `frontend` (React/nginx), `proxy` (nginx) und `certbot` (Let's Encrypt). Mit einem öffentlichen `PUBLIC_HOSTNAME` holt certbot automatisch ein Zertifikat, zuerst im Staging-Modus. Für den Betrieb hinter einem vorhandenen Reverse-Proxy und für alle Einstellungen siehe [`docs/betrieb.md`](docs/betrieb.md).

Danach im Log das Setup-Token suchen (`docker compose logs api | grep -o "setup token: [A-Za-z0-9_-]*"`) und die Web-Oberfläche öffnen. Der Setup-Assistent führt durch die Ersteinrichtung.

## Betrieb

Alles Weitere steht im [Betriebshandbuch](docs/betrieb.md):

| Thema | Kurz |
|---|---|
| [Ausgehende Adresse](docs/betrieb.md#ausgehende-adresse-outbound-ip) | feste Outbound-IP per SNAT (`deploy/egress/`) oder Egress-Proxy |
| [Selbsttests](docs/betrieb.md#selbsttests) | TLS-Zertifikat und Outbound-IP prüfen (*Einstellungen → Selbsttests*) |
| [Überwachung](docs/betrieb.md#überwachung) | `/health`, Prometheus unter `/metrics`, Alarmregeln, JSON-Logs |
| [Backup und Restore](docs/betrieb.md#backup-und-restore) | `deploy/backup/backup.sh`, Restore in fünf Schritten |
| [Updates](docs/betrieb.md#updates) | sichern, neue Version, `docker compose up -d`; Flyway migriert |
| [Zertifikatswechsel](docs/betrieb.md#zertifikatswechsel) | TLS automatisch, Signaturschlüssel mit Vorankündigung |
| [Verfügbarkeit und Wartung](docs/betrieb.md#verfügbarkeit-und-wartung) | mehrere Instanzen, Wartungsankündigung 5 Werktage vorher |
| [Datenschutz](docs/betrieb.md#datenschutz-und-aufbewahrung) | Aufbewahrungsfrist für Endkundendaten (ADR-0010) |
| [Images prüfen](docs/betrieb.md#images-prüfen) | Cosign-Signatur und SBOM der Release-Images |
| [Partner anbinden](docs/partner-onboarding.md) | Datenaustausch, Anlage, Verbindungstest, Checkliste |

## Entwicklung

```bash
./gradlew build                 # Backend inkl. Tests (JDK 25 wird bei Bedarf automatisch geladen)
cd frontend && pnpm install && pnpm dev
```

Die Web-Oberfläche, ihre Regeln und die E2E-Tests beschreibt [`docs/frontend.md`](docs/frontend.md).

Alle Befehle und Arbeitsregeln stehen in [`CLAUDE.md`](CLAUDE.md).

## Zertifizierung

Die Software soll in beiden Rollen den Konformitätstest (KFT) bestehen und ein **Softwarezertifikat** des AK S&P erhalten. Betreiber einer zertifizierten Version können dann beim AK ein Schnittstellenzertifikat beantragen, das auf diese Software verweist. Details und offene Fragen stehen in [`docs/AUFGABE-SPRI.md`, Kap. 3](docs/AUFGABE-SPRI.md#3-zertifizierungsstrategie).

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [`docs/AUFGABE-SPRI.md`](docs/AUFGABE-SPRI.md) | Anforderungen, Architektur, Teststrategie, Meilensteine |
| [`docs/api/`](docs/api/) | REST-API mit Beispielen, [`openapi.json`](docs/api/openapi.json) |
| [`docs/betrieb.md`](docs/betrieb.md) | Installation und Betrieb |
| [`docs/partner-onboarding.md`](docs/partner-onboarding.md) | Anbindung eines neuen Partners |
| [`docs/kft-szenarien.md`](docs/kft-szenarien.md) | KFT-Testfälle, Simulator, Selbsttest beider Rollen |
| [`docs/frontend.md`](docs/frontend.md) | Web-Oberfläche: Aufbau, Regeln, E2E-Tests |
| [`docs/spec-abweichungen.md`](docs/spec-abweichungen.md) | Auslegungen der Spezifikation |
| [`docs/grundlagen/`](docs/grundlagen/) | Einführung in S/PRI für Entwickler, Glossar |
| `docs/adr/` | Architekturentscheidungen |
| [`CHANGELOG/`](CHANGELOG/) | Beschreibungen größerer Änderungen |
| [`CLAUDE.md`](CLAUDE.md) | Grundsätze und Arbeitsregeln für Entwickler |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Mitarbeit |
| [`SECURITY.md`](SECURITY.md) | Sicherheitslücken melden |
| [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) | Verhaltenskodex |

## Spezifikationsdokumente

Die Dokumente des AK S&P sind urheberrechtlich geschützt. Sie sind deshalb nicht im Repository enthalten.

```bash
./scripts/fetch-spec.sh            # lädt die Dokumente nach docs/spec-original/
python3 scripts/gen-testmatrix.py  # erzeugt docs/ANHANG-A-Testfaelle.md (benötigt LibreOffice)
```

Die Meldungscodetabelle gibt es nur online im [Confluence des AK](https://aksup.atlassian.net/wiki/spaces/SPE/pages/1221034635/SPRI+Meldungscodetabelle). Sie wird manuell als CSV exportiert und unter `docs/spec-original/SPRI_Meldungscodetabelle.csv` abgelegt.

## Lizenz

[MIT](LICENSE). „S/PRI“ bezeichnet die Spezifikation des AK S&P. open-spri ist ein unabhängiges Projekt und kein Angebot des Arbeitskreises.
