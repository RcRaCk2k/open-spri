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

### Teil 3: Auftraggeber – Quellen, Import, Suche, Auftragsvorlage
- **Quellen (`AvailabilitySources`):** je Partner `REPLIKAT` (Download-Verzeichnis) oder `ONLINE` (andere open-spri-Instanz). Zugangsdaten mit Basic-Auth oder als Bearer-Token, verschlüsselt mit `OPENSPRI_MASTER_KEY`. REST `GET/POST /replikat/sources`, `PUT/DELETE /replikat/sources/{id}`.
- **Import (`ReplikatImporter`):** täglich (`openspri.replikat.import-cron`, Standard 07:00) und per `POST /replikat/sources/{id}/run`.
  - Metadatei von heute, sonst von gestern; ist der Tag schon importiert, passiert nichts
  - Delta nur auf dem Import des Vortags
  - MD5- und XSD-Prüfung, Grenze 1 GiB entpackt
  - Warnungen für einen abweichenden Carrier Code und für unplausible Koordinaten (SA-017)
  - jeder Lauf steht in `replikat_import` (`GET …/imports`)
- **Suche (`AvailabilitySearch`, SPI `AvailabilityProvider`):**
  - `ReplikatProvider` sucht im importierten Bestand nach Adresse, HomeID, Adress-ID oder Koordinaten mit Umkreis
  - `OnlineProvider` fragt die Instanz des Partners
  - Ein Partner, der nicht antwortet, steht in `errors`
  - REST `GET /availability/search`
- **Auftragsvorlage (`ReplikatOrderTemplate`):** `POST /availability/order-template` übernimmt einen Treffer nach Replikat 1.7 Kap. 4 in einen NEU-Auftrag: StandortA, HomeID, Produkt und Positionen, Konnektivität, Zeitfenster. Die Antwort nennt, was noch fehlt.
- Migration V14: `availability_source`, `replikat_import`, `partner_location` (mit Koordinaten für die Umkreissuche)

## Auswirkungen
- S/PRI-Schnittstelle: keine Änderung
- Build: neues Modul `replikat`, Download der Replikat-Anlagen
- REST-API: `/availability`, `/availability/sites…`, `/replikat/releases…`; Partnerzugriff `/replikat/…`; Auftraggeber `/replikat/sources…`, `/availability/search`, `/availability/order-template`
- Sicherheit: Rolle `PARTNER`, Scopes `partner:availability` und `availability:admin`; Basic-Auth mit API-Token
- Datenbank: V13, V14
- Konfiguration: `OPENSPRI_REPLIKAT_EXPORT_CRON`, `OPENSPRI_REPLIKAT_IMPORT_CRON`

## Tests
- Teil 3:
  - `ReplikatImporterTest`: Vollimport, Delta am Folgetag, kein zweites Anwenden, Replikat von gestern, MD5-Fehler ohne Änderung am Bestand, Plausibilitätswarnung
  - `ReplikatOrderTemplateTest`: Abbildung nach Kap. 4, fremde Produkte und HomeIDs
  - `AvailabilityApiTest`: Die Instanz importiert ihr eigenes Replikat über `/replikat/` mit Partner-Token und fragt sich selbst online ab. Geprüft werden Suche nach Adresse, HomeID und Umkreis sowie die Auftragsvorlage.
  - `ApplicationIntegrationTest`: V14 auf MariaDB, Umkreissuche, Delta
- Teil 2: `ReplikatExporterTest` (Voll- und Deltareplikat über drei Tage mit ADD, MOD, DEL, UCH; Felder und Kontingente der Freigabe; 31 Tage); `ReplikatXmlTest` (250 Datensätze ohne Leerraum); `AvailabilityApiTest` (Standorte per REST, CSV und Replikat-Import mit XSD-Prüfung und Zeilennummern; Freigabe, Export, Download mit Bearer und Basic-Auth, MD5 aus der Metadatei, XSD des gelieferten Replikats, 401/403/404, fremde IP, Online-Abfrage mit vereinbarten Feldern); `ApplicationIntegrationTest` (alle neuen Tabellen auf MariaDB)
- Teil 1: `ReplikatXmlTest` (die drei Beispiel-XMLs der Anlage 3 sind gültig, lesbar und überstehen Lesen → Schreiben → Prüfen → Lesen unverändert; Felder des Konnektivitätsbeispiels; ungültiges Replikat mit Zeilennummer; Paket mit ZIP, Metadatei und MD5)
