# CL-0010: M6 – Verfügbarkeit, Replikat und Online-Abfrage

| | |
|---|---|
| Datum | 2026-11-02 |
| Meilenstein | M6 |
| Bezug | AUFGABE 5.4, D-05, D-13, 8.5; AK S&P „Replikate zur Verfügbarkeitsprüfung“ 1.7 mit Anlagen (Replikat.xsd, Beispiel-XMLs) |

## Motivation
Als Leistungserbringer müssen wir unsere Verfügbarkeiten täglich als Replikat bereitstellen und online abfragbar machen. Als Auftraggeber importieren wir die Replikate der Partner, suchen darin und übernehmen einen Treffer in einen Auftrag (D-05).

## Änderungen

### Teil 1: Modul `replikat`, Fachmodell, XSD 1.7
- Fachmodell `de.openspri.domain.availability`: `Location` (ein Datensatz `ds`), `ProductAvailability` (`pl/p`), `TechnologyAvailability` (`tl/t`), `ChangeMark` (`ak`), `OrderKind` (`auftragsart`). Feldnamen englisch, jedes Feld nennt sein Replikat-Element. `LocationKey` ist die Identität im Delta (SA-016).
- Modul `replikat`:
  - lädt `Anlagen_Replikat_v1.7.zip` beim Build (Prüfsumme, nicht im Repository) und erzeugt daraus die JAXB-Klassen der `Replikat.xsd`
  - `ReplikatXml`: Schemaprüfung; Lesen und Schreiben Datensatz für Datensatz (StAX mit JAXB je `ds`), XML gehärtet
  - `ReplikatFiles`: Dateinamen, ZIP (deflate), MD5, Paket mit Metadatei; `ReplikatMeta` schreibt und liest die Metadatei
- SA-016 (Name des Deltareplikats, Identität eines Datensatzes), SA-017 (vertauschte Koordinaten in den Beispielen)

### Teil 2: Leistungserbringer – Standorte, Replikat, Download, Online-Abfrage
- **Eigene Standorte (D-13):** `AvailabilitySites`; REST `GET/POST/PUT/DELETE /availability/sites`; Import `POST /availability/sites/import` als Replikat (voll ersetzt, Delta nach Kennzeichen) oder CSV (`SiteCsv`), Modus `replace` oder `merge`. Jeder Datensatz wird gegen die XSD geprüft.
- **Replikat-Export (`ReplikatExporter`):** täglich (`openspri.replikat.export-cron`, Standard 03:00) je freigegebenem Partner ein Vollreplikat mit Änderungskennzeichen und auf Wunsch ein Delta mit ADD, MOD und DEL. Der Exportstand je Partner wird gespeichert, damit DEL die letzte Adresse trägt. Felder und Kontingente sind je Partner frei wählbar, Aufbewahrung 31 Tage.
- **Freigaben:** `GET /replikat/releases`, `PUT/DELETE /replikat/releases/{partnerId}`, `POST …/run`, `GET …/files`.
- **Download für Partner:** `GET /replikat/` und `GET /replikat/{datei}`, Online-Abfrage `GET /api/v1/availability` (Antwort im Replikat-Format, SA-003). Zugriff nur mit Partner-Token aus einer freigeschalteten IP (`PartnerAccess`).
- **Partner-Tokens (AUFGABE 7.5):** Rolle `PARTNER` mit Scope `partner:availability`, gebunden an einen Partner (`ApiToken.partnerId`, `Actor.partnerId`). Basic-Auth mit dem Token als Passwort wird jetzt ebenfalls angenommen. Neuer Scope `availability:admin` (ADMIN, OPERATOR_LE).
- **Fehler gefunden und behoben:** `ReplikatXml.read` übersprang jeden zweiten Datensatz, wenn Datensätze ohne Leerraum aufeinander folgen. Die Beispiele haben nur einen Datensatz, der Exporter-Test hat es gezeigt.
- Migration V13: `availability_site`, `replikat_release`, `replikat_snapshot`, `replikat_export_state`, `api_token.partner_id`.
- SA-018 (Client-Zertifikat beim Download); SA-003 als umgesetzt markiert. Doku: `docs/api/verfuegbarkeit.md`, `auth.md`, `betrieb.md`.

## Auswirkungen
- S/PRI-Schnittstelle: keine Änderung
- Build: neues Modul `replikat`, Download der Replikat-Anlagen
- REST-API: `/availability`, `/availability/sites…`, `/replikat/releases…`; Partnerzugriff `/replikat/…`
- Sicherheit: Rolle `PARTNER`, Scopes `partner:availability` und `availability:admin`; Basic-Auth mit API-Token
- Datenbank: V13
- Konfiguration: `OPENSPRI_REPLIKAT_EXPORT_CRON`

## Tests
- Teil 2: `ReplikatExporterTest` (Voll- und Deltareplikat über drei Tage mit ADD, MOD, DEL, UCH; Felder und Kontingente der Freigabe; 31 Tage); `ReplikatXmlTest` (250 Datensätze ohne Leerraum); `AvailabilityApiTest` (Standorte per REST, CSV und Replikat-Import mit XSD-Prüfung und Zeilennummern; Freigabe, Export, Download mit Bearer und Basic-Auth, MD5 aus der Metadatei, XSD des gelieferten Replikats, 401/403/404, fremde IP, Online-Abfrage mit vereinbarten Feldern); `ApplicationIntegrationTest` (alle neuen Tabellen auf MariaDB)
- Teil 1: `ReplikatXmlTest` (die drei Beispiel-XMLs der Anlage 3 sind gültig, lesbar und überstehen Lesen → Schreiben → Prüfen → Lesen unverändert; Felder des Konnektivitätsbeispiels; ungültiges Replikat mit Zeilennummer; Paket mit ZIP, Metadatei und MD5)
