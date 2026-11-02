# Web-Oberfläche (Frontend)

Die Web-Oberfläche ist eine React-Anwendung in `frontend/`. Sie nutzt **ausschließlich** die öffentliche REST-API `/api/v1`, genau wie jedes Fremdsystem (Grundsatz 4). Die Technikauswahl steht in [ADR-0009](adr/ADR-0009-frontend-stack.md).

## Aufbau

| Pfad | Inhalt |
|---|---|
| `src/api/schema.d.ts` | aus `docs/api/openapi.json` erzeugte Typen (`pnpm generate:api`), nie von Hand ändern |
| `src/api/client.ts` | typisierter Client (`openapi-fetch`), CSRF-Header, `call()` liefert die Daten oder einen `ApiError` (RFC 9457) |
| `src/auth/` | Anmeldung (`useMe`), Setup-Status, Scopes |
| `src/navigation.ts` | alle Seiten mit dem nötigen Scope; die Navigation zeigt nur erlaubte Seiten |
| `src/App.tsx` | Routen; bis zur Ersteinrichtung führt jede Seite zum Assistenten, danach braucht jede Seite eine Anmeldung |
| `src/pages/` | Seiten (Setup, Anmeldung, Dashboard, Audit-Log, Benutzer und Tokens …), `src/pages/orders/` für Aufträge, Assistent, Arbeitsvorrat, Providerwechsel und KDLE, `src/pages/availability/` für die Verfügbarkeitssuche |
| `src/theme.ts` | Mantine-Theme mit Farben, die WCAG 2.1 AA erfüllen |
| `e2e/` | Playwright-Tests |

## Seiten (AUFGABE 8.6)

| Seite | Pfad | Scope | API |
|---|---|---|---|
| Setup-Assistent | `/setup` | Setup-Token | `GET/POST /setup` |
| Anmeldung | `/login` | – | `POST /auth/login`, `GET /auth/me` |
| Dashboard: offene Aufträge, Fristen TAM/MTAM und AKM-PV, Zustellfehler | `/` | `orders:read`, `deliveries:read` | `/orders`, `/pv-requests`, `/deliveries` |
| Auftragsliste | `/orders` | `orders:read` | `GET /orders` |
| Auftrag mit Verlauf, Aktionen laut `allowedActions`, Roh-XML, Entwurf | `/orders/{id}` | `orders:read`, Aktionen `orders:write` bzw. `le:messages:write` | `/orders/{id}…`, `/messages/{id}/raw`, `/deliveries/{id}/payload` |
| Auftrag anlegen (Assistent je Geschäftsfall) | `/orders/new` | `orders:write` | `POST /orders/compose`, `POST /orders` |
| LE-Arbeitsvorrat | `/work-queue` | `orders:read` | `GET /orders?role=LEISTUNGSERBRINGER&open=true` |
| Providerwechsel-Anfragen (AKM-PV, RUEM-PV) | `/pv-requests` | `orders:read`, Antwort `orders:write` | `/pv-requests` |
| Kündigung durch uns (KDLE) | `/terminations/new` | `le:messages:write` | `POST /terminations` |
| Verfügbarkeitssuche | `/availability` | `availability:read` | `/availability/search`, `/availability/order-template` |
| Bestand | `/inventory` | `orders:read` | `GET /inventory` |
| Partner | `/partners`, `/partners/{id}` | `partners:read`, Ändern `partners:admin` | `/partners…` |
| Produkte und Wechselmatrix | `/products` | `partners:read`, Ändern `partners:admin` | `/products`, `/product-change-matrix` |
| Meldungscodes | `/message-codes` | `orders:read`, Import `message-codes:admin` | `/message-codes` |
| Verfügbarkeit verwalten: eigene Standorte, Replikat-Freigaben, Quellen | `/availability/manage` | `availability:read`, Ändern `availability:admin` | `/availability/sites`, `/replikat/releases`, `/replikat/sources` |
| Zustellmonitor: Postausgang, Eingang, Wartung | `/deliveries` | `deliveries:read`, Eingreifen `deliveries:write` | `/deliveries`, `/inbox`, `/maintenance-announcements` |
| Audit-Log | `/audit` | `audit:read` | `GET /audit` |
| Benutzer und Tokens | `/users` | `users:admin` | `/users`, `/tokens` |
| Webhooks | `/webhooks` | `webhooks:admin` | `/webhooks` |
| Einstellungen und Signaturschlüssel | `/settings` | `settings:admin` | `/settings`, `/settings/signing-key` |

## Regeln für neue Seiten

1. Zuerst muss die Funktion in der API vorhanden und in `docs/api/` beschrieben sein. Danach `pnpm generate:api` ausführen.
2. Die API wird nur über `api.GET/POST/…` aus `src/api/client.ts` mit `call()` aufgerufen, Daten werden mit TanStack Query geladen.
3. Formulare werden mit `react-hook-form` und `zod` geprüft. Fehler der API zeigt `ErrorAlert` an, Zeilenfehler (`errors`) eingeschlossen.
4. Die Seite kommt mit ihrem Scope in `src/navigation.ts`. Der E2E-Test `api-only.spec.ts` prüft dann jede Seite der Navigation automatisch: Er ruft sie auf, erlaubt nur Aufrufe an `/api/v1` und prüft mit axe WCAG 2.1 AA im hellen und im dunklen Farbschema.
5. Aktionen, die eine S/PRI-Nachricht auslösen, senden je Dialog einen eigenen `Idempotency-Key` (`newIdempotencyKey()` in `src/hooks.ts`). Angeboten werden nur Aktionen aus `allowedActions`.
6. Die Route wird in `src/App.tsx` mit `page(() => import(…))` eingetragen, damit die Seite erst bei Bedarf geladen wird.
7. Farben kommen aus dem Theme (`color="red"`, `c="dimmed"`). Feste Schattierungen wie `c="red.8"` sind verboten, weil sie in einem der Farbschemata unter 4,5:1 fallen können. `src/theme.test.ts` prüft das.
8. Texte sind deutsch, Bezeichner im Code englisch, S/PRI-Begriffe bleiben wie im Standard.

## Tests

```bash
cd frontend
pnpm typecheck && pnpm test          # TypeScript und Vitest (Logik, Komponenten in jsdom)
```

### E2E-Tests (Playwright)

Die E2E-Suite läuft gegen eine laufende Instanz. Sie richtet sie über den Setup-Assistenten ein, falls das noch nicht geschehen ist, und meldet sich als Administrator an. In der CI läuft sie gegen den kompletten Compose-Stack hinter dem Proxy (`E2E_BASE_URL=https://localhost`).

Lokal mit leerer Datenbank:

```bash
docker run -d --name openspri-e2e-db -e MARIADB_DATABASE=openspri -e MARIADB_USER=openspri \
  -e MARIADB_PASSWORD=e2e-db -e MARIADB_ROOT_PASSWORD=e2e-root -p 127.0.0.1:3306:3306 mariadb:11.8
SPRING_DATASOURCE_URL=jdbc:mariadb://127.0.0.1:3306/openspri SPRING_DATASOURCE_USERNAME=openspri \
  SPRING_DATASOURCE_PASSWORD=e2e-db OPENSPRI_MASTER_KEY=$(openssl rand -base64 32) \
  ./gradlew :app:bootRun | tee /tmp/api.log &
cd frontend && pnpm dev &
pnpm exec playwright install chromium                 # einmalig
E2E_SETUP_TOKEN=$(grep -A3 "setup token" /tmp/api.log | sed -n 3p | tr -d ' ') pnpm e2e
```

| Variable | Bedeutung | Standard |
|---|---|---|
| `E2E_BASE_URL` | Adresse der Oberfläche | `http://localhost:5173` |
| `E2E_SETUP_TOKEN` | Setup-Token aus dem API-Log; nur nötig, solange die Einrichtung offen ist | – |
| `E2E_ADMIN_USER`, `E2E_ADMIN_PASSWORD` | Administrator, den der Test anlegt bzw. mit dem er sich anmeldet | `admin`, erfundenes Passwort |

Die Tests verwenden nur erfundene Daten (Firma „Testnetz E2E GmbH“, Carrier Code `TSTE2E`). Bei einem Fehler legt Playwright Trace und Bericht in `test-results/` und `playwright-report/` ab: `pnpm exec playwright show-trace …`.
