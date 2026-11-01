# open-spri

Freie, offene Implementierung der **S/PRI-Schnittstelle** (Supplier/Partner Requisition Interface, V4.4) des [Arbeitskreises Schnittstellen & Prozesse](https://ak-spri.de/spri/). Mit ihr bestellen Telekommunikationsanbieter Vorleistungsprodukte (FTTH, FTTB, FTTC) bei anderen Netzbetreibern und bieten ihre eigenen Anschlüsse über einen Standard an.

> **Status: Spezifikationsphase.** Es gibt noch keine lauffähige Software. Den Umfang beschreibt die Aufgabenbeschreibung [`docs/AUFGABE-SPRI.md`](docs/AUFGABE-SPRI.md). Installation und Betrieb werden ab Meilenstein M0 hier dokumentiert.

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

## Zertifizierung

Die Software soll in beiden Rollen den Konformitätstest (KFT) bestehen und ein **Softwarezertifikat** des AK S&P erhalten. Betreiber einer zertifizierten Version können dann beim AK ein Schnittstellenzertifikat beantragen, das auf diese Software verweist. Details und offene Fragen stehen in [`docs/AUFGABE-SPRI.md`, Kap. 3](docs/AUFGABE-SPRI.md#3-zertifizierungsstrategie).

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [`docs/AUFGABE-SPRI.md`](docs/AUFGABE-SPRI.md) | Anforderungen, Architektur, Teststrategie, Meilensteine |
| `docs/api/` | REST-API mit Beispielen (ab M4) |
| `docs/grundlagen/` | Einführung in S/PRI für Entwickler (ab M2) |
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
