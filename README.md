# open-spri

Freie, offene Implementierung der **S/PRI-Schnittstelle** (Supplier/Partner Requisition Interface, V4.4) des [Arbeitskreises Schnittstellen & Prozesse](https://ak-spri.de/spri/). Mit ihr bestellen Telekommunikationsanbieter Vorleistungsprodukte (FTTH, FTTB, FTTC) bei anderen Netzbetreibern und bieten ihre eigenen Anschlüsse über einen Standard an.

> **Status: in Entwicklung (Meilenstein M5 läuft: Simulator und KFT-Szenarien, Stand in [`docs/kft-szenarien.md`](docs/kft-szenarien.md#stand)).**
> - Der S/PRI-Endpunkt nimmt signierte Aufträge und Meldungen an, prüft sie gegen XSD und Anlage 1 und quittiert sie mit TEQ.
> - Aufträge und Meldungen werden fachlich verarbeitet, mit Zustandsautomaten für alle Geschäftsfälle, abgeglichen mit den 211 Meldungsfolgen der KFT-Testfallliste.
> - Ausgehende Nachrichten laufen über einen Postausgang mit garantierter Reihenfolge, Wiederholungen und Ausfall-Mail, auch mit mehreren Instanzen.
> - REST-API mit Setup-Assistent, API-Tokens und Benutzern, Partnerverwaltung mit Verträgen, Vollmachten und Projektvereinbarungen, Produktkatalog und Produktwechselmatrix, Auftragsentwürfen, Aufträgen und Meldungen in allen Rollen (auch KDLE und RUEM-PV), Wartungsankündigung, Zustellmonitor, Nachrichtenarchiv, Event-Feed, Webhooks und Idempotenz. Beschreibung mit curl-Beispielen in [`docs/api/`](docs/api/README.md). Was in M4 noch fehlt, steht in [`CHANGELOG/CL-0008`](CHANGELOG/CL-0008-m4-rest-api.md#noch-offen-in-m4).
> - Als Nächstes folgen Simulator und KFT-Szenarien (M5). Umfang und Fahrplan stehen in [`docs/AUFGABE-SPRI.md`](docs/AUFGABE-SPRI.md).

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

## Entwicklung

```bash
./gradlew build                 # Backend inkl. Tests (JDK 25 wird bei Bedarf automatisch geladen)
cd frontend && pnpm install && pnpm dev
```

Alle Befehle und Arbeitsregeln stehen in [`CLAUDE.md`](CLAUDE.md).

## Zertifizierung

Die Software soll in beiden Rollen den Konformitätstest (KFT) bestehen und ein **Softwarezertifikat** des AK S&P erhalten. Betreiber einer zertifizierten Version können dann beim AK ein Schnittstellenzertifikat beantragen, das auf diese Software verweist. Details und offene Fragen stehen in [`docs/AUFGABE-SPRI.md`, Kap. 3](docs/AUFGABE-SPRI.md#3-zertifizierungsstrategie).

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [`docs/AUFGABE-SPRI.md`](docs/AUFGABE-SPRI.md) | Anforderungen, Architektur, Teststrategie, Meilensteine |
| [`docs/api/`](docs/api/) | REST-API mit Beispielen, [`openapi.json`](docs/api/openapi.json) |
| [`docs/betrieb.md`](docs/betrieb.md) | Installation und Betrieb |
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
