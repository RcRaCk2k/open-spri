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

## Auswirkungen
- S/PRI-Schnittstelle: keine Änderung
- REST-API: neu `GET /api/v1/audit`
- Datenbank: keine Migration
- Konfiguration: keine; in der CI wird `OPENSPRI_MASTER_KEY` gesetzt
- Betrieb: Die Ersteinrichtung geht jetzt vollständig in der Oberfläche.

## Tests
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
