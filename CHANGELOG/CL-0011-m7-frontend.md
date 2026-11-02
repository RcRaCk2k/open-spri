# CL-0011: M7 – Web-Oberfläche

| | |
|---|---|
| Datum | 2026-11-02 |
| Meilenstein | M7 |
| Bezug | AUFGABE 8.6, 10.1 Punkt 9, NF-10; Grundsatz 4; ADR-0009 |

## Motivation
Bisher zeigte die Oberfläche nur eine Statusseite (M0). M7 bringt alle Seiten aus AUFGABE 8.6. Sie arbeiten ausschließlich über die öffentliche REST-API, und eine Playwright-Suite gegen den kompletten Stack weist das nach.

## Änderungen

### Teil 1: Grundgerüst, Setup, Anmeldung, Dashboard, Audit-Log, Benutzer und Tokens
- **ADR-0009:** Mantine 9, TanStack Query 5, React Router 8, `react-hook-form` und `zod` 4, Vitest mit Testing Library, Playwright mit axe-core. CLAUDE.md ist angepasst.
- **Grundgerüst:**
  - `src/api/client.ts`: typisierter Client mit CSRF-Header für die Session und `ApiError` mit RFC-9457-Inhalt
  - Eine 401 beendet die Sitzung, danach erscheint die Anmeldung.
  - Navigation nach Scopes (`src/navigation.ts`)
  - AppShell mit Sprunglink zum Inhalt
  - eigene Fehlerseite
- **Setup-Assistent:**
  - fünf Schritte: Setup-Token, Firma (ICC, Rollen, Optionen, WBCI-Modus, S/PRI-Endpunkt, Outbound-IPs), Administrator, Signaturschlüssel (erzeugen oder PKCS#12 übernehmen), Zusammenfassung
  - Ein falsches Token führt zurück zum ersten Schritt.
  - Nach der Einrichtung führt die Seite zur Anmeldung.
- **Anmeldung:** Nach der Anmeldung geht es zur ursprünglich angeforderten Seite (`?next=`, nur Pfade dieser Anwendung).
- **Dashboard:**
  - offene Aufträge als LE und als AG
  - Zustellfehler (UNDELIVERABLE, REJECTED, UNCLEAR, DELIVERED_WITH_ERROR)
  - offene Terminabstimmung (TAM/MTAM) mit dem nächsten Schritt je Rolle
  - fällige RUEM-PV mit Antwortfrist
  - Alle Daten kommen aus den bestehenden Endpunkten.
- **Audit-Log:**
  - neuer Endpunkt `GET /api/v1/audit` (Scope `audit:read`) mit den Filtern Akteur, Aktion (Präfix), Ziel und Zeitraum, Blättern per Cursor
  - Port `AuditTrail` im Modul `auth-api`; `JdbcAuditLog` maskiert `%` und `_`
  - Die Seite filtert beim Tippen.
- **Benutzer und Tokens:**
  - Benutzer anlegen, Rolle ändern, sperren
  - Tokens ausstellen (Rolle, Partner bei Rolle PARTNER, eingeschränkte Scopes, Ablaufdatum). Das Geheimnis erscheint nur einmal, mit Kopierknopf.
  - Tokens widerrufen
- **Barrierefreiheit:**
  - Primärfarbe, Nebentexte und die helle Variante sind dunkler als Mantines Standard. axe fand vorher Kontraste von 3,3:1 bzw. 4,3:1.
  - deutsche Bezeichnungen für Mantines eingebaute Schalter
- **Fehler gefunden und behoben:**
  - Der letzte Klick auf „Weiter“ im Assistenten schickte das Formular schon ab. React tauschte den Button während des Klicks gegen den Submit-Button aus.
  - Der Filter im Audit-Log las `currentTarget` erst im State-Updater, wo es bereits `null` ist.
- **CI:** Der Compose-Job startet den Stack und führt danach `pnpm e2e` gegen `https://localhost` aus. Das Setup-Token kommt aus dem API-Log, der Master-Key wird je Lauf neu erzeugt. Bei einem Fehler wird der Playwright-Bericht hochgeladen.
- **Doku:** `docs/frontend.md` (Aufbau, Regeln für neue Seiten, E2E lokal), Audit-Log in `docs/api/auth.md`

### Teil 2: Aufträge, Arbeitsvorrat, Providerwechsel, KDLE
- **Auftragsliste** (`/orders`):
  - Filter nach Rolle, Geschäftsfall, Phase und offen/alle, Präfixsuche
  - Die Filter stehen in der URL, eine gefilterte Liste lässt sich also als Lesezeichen speichern.
  - Blättern per Cursor
- **Arbeitsvorrat LE** (`/work-queue`): offene Aufträge der Partner an uns, mit Einstieg in die Kündigung durch uns.
- **Auftragsdetail** (`/orders/{id}`):
  - Daten des Auftrags und offene Änderungen
  - „Nächste Schritte“ zeigt nur `allowedActions`, und nur solche, für die die Anmeldung den Scope hat (`le:messages:write` bzw. `orders:write`).
  - Verlauf als Zeitleiste mit Richtung, Codes samt Text aus der Meldungscodetabelle und Befunden der Prüfungen
  - Roh-XML eingehender Nachrichten aus dem Archiv, für ausgehende der gesendete Inhalt aus dem Postausgang. Der Abruf steht im Audit-Log.
- **Aktionen:**
  - Meldung senden: Meldungscodes nur passend zu Meldungstyp und Geschäftsfall, ABM mit Feld für den verbindlichen Liefertermin, weitere Meldungsattribute als JSON
  - Storno, Terminverschiebung, Reklamation (Entstörauftrag als JSON)
  - Jeder Dialog sendet einen eigenen `Idempotency-Key`, ein wiederholter Klick löst also keine zweite Nachricht aus.
- **Entwürfe:** Den Auftrag als XSD-JSON ansehen und ändern, senden oder verwerfen.
- **Providerwechsel-Anfragen** (`/pv-requests`): AKM-PV nach Antwortfrist sortiert, mit Markierung ab zwei Tagen vor der Frist; RUEM-PV mit Zustimmung oder Ablehnung.
- **Kündigung durch uns** (`/terminations/new`): KDLE-Formular, vorbefüllbar über URL-Parameter (Partner, Vertrags-, Auftraggebernummer, LineID).
- **Seiten bei Bedarf laden:** Jede Seite wird erst beim ersten Aufruf geladen. Der Einstieg mit Anmeldung und Setup ist rund 180 kB groß (gzip).
- **Barrierefreiheit:**
  - Die Texte der hellen und der Outline-Variante sind in allen Farben dunkler. Orange auf Weiß lag vorher unter 4,5:1.
  - Fehlertexte in dunklerem Rot
  - deutsche Namen für die Schließen-Knöpfe von Dialogen und Meldungen; reduzierte Bewegung wird beachtet

### Teil 3: Auftragsassistent, Verfügbarkeitssuche, Bestand
- **Neuer Endpunkt `POST /api/v1/orders/compose`** (`OrderComposer` im Modul `spri-soap`):
  - setzt aus einfachen Angaben das `auftrag`-JSON jedes Geschäftsfalls des Auftraggebers zusammen (NEU, KUE-AG, LAE, AEN-LMAE, PV, EST, DIAG-GET, DIAG-SET), mit allen `@type`-Angaben
  - `missing` nennt fehlende XSD-Pfade, auch Pflichtfelder der XSD wie die Telefonnummer des Auftragsmanagements und den Vornamen im Endkundenkontakt.
  - Die XSD-Kenntnis liegt damit im Backend, nicht im Frontend.
- **Auftragsassistent** (`/orders/new`):
  - Partner und Geschäftsfall wählen; danach zeigt der Assistent nur die Felder des Geschäftsfalls
  - Leitung aus dem Bestand übernehmen; Produkte mit Vorschlägen aus dem Produktkatalog des Partners
  - Endkunde (Person oder Firma) und Standort, Störung, Diagnose-Operationen
  - „Prüfen und Vorschau“ zeigt fehlende Angaben und das XSD-JSON; das JSON lässt sich vor dem Speichern noch ändern
  - Gespeichert wird als Entwurf; gesendet wird auf der Auftragsseite.
- **Verfügbarkeitssuche** (`/availability`):
  - Suche nach Adresse, HomeID/Adress-ID oder Koordinaten mit Umkreis, optional nur bei einem Partner
  - Treffer mit Produkten, Bandbreiten, Ausbaustatus, Konnektivität, Eigentümerdaten und HomeIDs
  - Partner ohne Antwort werden genannt.
  - „Auftrag anlegen“: Produkte und HomeID wählen, `POST /availability/order-template` (Replikat 1.7 Kap. 4) liefert die Vorlage, der Assistent öffnet sich vorbefüllt mit den Hinweisen aus dem Replikat.
- **Bestand** (`/inventory`):
  - Verträge nach Rolle, LineID und aktiv/alle, mit Verweis auf den auslösenden Auftrag
  - je aktiver Leitung des Partners die passenden Aufträge (KUE-AG, LAE, AEN-LMAE, EST, DIAG) im vorbefüllten Assistenten
  - für unsere eigenen Leitungen die KDLE
- **Fehler gefunden und behoben:**
  - `InventoryController.ContractDto` und der Rahmenvertrag `AgreementController.ContractDto` hatten denselben Schemanamen. Die OpenAPI-Datei beschrieb `/inventory` deshalb mit den Feldern des Rahmenvertrags. Das Schema heißt jetzt `InventoryContractDto`. Die neue ArchUnit-Regel `restSchemaNamesAreUnique` verhindert solche Doppelungen künftig.
  - Gefüllte Buttons fielen beim Überfahren mit der Maus auf einen Kontrast von 3,9:1. Die Hover-Farbe ist jetzt dunkler.

## Auswirkungen
- S/PRI-Schnittstelle: keine Änderung
- REST-API: neu `GET /api/v1/audit` und `POST /api/v1/orders/compose`; Schema `InventoryContractDto` statt des falsch zugeordneten `ContractDto` bei `/inventory` (die Felder der Antwort sind unverändert)
- Datenbank: keine Migration
- Konfiguration: keine; in der CI wird `OPENSPRI_MASTER_KEY` gesetzt
- Betrieb: Die Ersteinrichtung geht jetzt vollständig in der Oberfläche.

## Tests
- Teil 3:
  - `OrderApiTest`: Für jeden der acht Geschäftsfälle wird ein Auftrag zusammengesetzt und als Entwurf gespeichert (XSD und Anlage 1 bestanden). Dazu fehlende Angaben, KUE-LE und unbekannter Geschäftsfall.
  - `ArchitectureTest.restSchemaNamesAreUnique`, geprüft auch mit dem alten Namen (schlägt fehl)
  - Playwright `new-order.spec.ts`:
    - KUE-AG im Assistenten mit fehlenden Angaben, Vorschau und Entwurf
    - Verfügbarkeit: Die Instanz gibt ihren Standort frei und fragt sich selbst online ab (Partner-Token, Online-Quelle). Danach Suche, Treffer, Auftragsvorlage und vorbefüllter Assistent.
  - Vitest: Formular → Compose-Anfrage je Geschäftsfall, Vorbefüllung aus Auftragsvorlage und Bestand, Replikat-Datensatz lesen
- Teil 2:
  - Playwright `orders.spec.ts`:
    - Partner und Entwurf entstehen über die API.
    - In der Oberfläche: suchen, Entwurf ansehen und senden, Verlauf, gesendetes XML, nur „Storno“ als nächster Schritt, Storno senden
    - axe auf Auftrag, Dialog und KDLE-Formular
    - Arbeitsvorrat, Providerwechsel-Anfragen und Pflichtfelder der KDLE
  - Vitest `MessageForm.test.tsx`: Codes gefiltert nach Geschäftsfall; die ABM geht mit Liefertermin und `Idempotency-Key` an die API. Dazu Beschriftung und Scopes der Aktionen sowie das Einrücken von XML.
- Teil 1:
  - Playwright gegen MariaDB, API und Oberfläche, 6 Tests:
    - Setup-Assistent mit Fehlern bei Token, Carrier Code und Passwortwiederholung
    - Anmeldung mit falschem Passwort, Weiterleitung und Abmeldung
    - Dashboard
    - Benutzer, Token und Widerruf mit den Einträgen im Audit-Log
    - jede Seite nur mit `/api/v1` und ohne axe-Befund (WCAG 2.1 AA, hell und dunkel)
  - Vitest, 19 Tests: Client (Cookie, CSRF, Fehler), Navigation, Datumsfunktionen, Setup-Formular, Anmeldeseite mit Weiterleitung in jsdom
  - `SecurityTest`: Audit-Suche mit Filtern, Cursor und Scopes
  - `ApplicationIntegrationTest`: Audit-Suche auf MariaDB, `%` und `_` als normale Zeichen
