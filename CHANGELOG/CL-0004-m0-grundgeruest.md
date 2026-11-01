# CL-0004: M0 – Grundgerüst, Container-Stack, CI

| | |
|---|---|
| Datum | 2026-11-01 |
| Meilenstein | M0 |
| Bezug | AUFGABE Kap. 7.4, 8.1, 8.2, 9, 11 (M0), 12; ADR-0003, ADR-0004 |

## Motivation

Die Entwicklung ist freigegeben (AUFGABE v1.0). M0 schafft das Fundament: Build, Tests, Container und CI. Alle weiteren Meilensteine bauen darauf auf.

## Änderungen

- **Gradle-Build:** Wrapper 9.8.0, Version Catalog, Convention-Plugins (`build-logic/`). Die Module `domain`, `persistence`, `rest-api` und `app` liegen unter `backend/`.
- **domain:** `InterfaceVersion` (Versionsregeln laut Spezifikation 10.4), `BusinessCase` und `ChangeIndicator` (Spezifikation 4.1, inkl. DIAG), jeweils mit Unit-Tests
- **persistence:** Flyway-Baseline `V1__baseline.sql` mit `tenant` (D-12, Standardmandant 1) und `setup_state`
- **rest-api:** `GET /api/v1/about` (Version, S/PRI-Version, Kennzeichen für zertifizierten Build), OpenAPI-Konfiguration, RFC-9457-Fehlerformat
- **app:** Spring-Boot-Anwendung, Health-Endpunkte `/health`, `/health/liveness`, `/health/readiness`, Swagger UI unter `/api/docs`
  - ArchUnit-Regeln: Die Domäne bleibt frei von Frameworks.
  - API-first-Prüfung: `docs/api/openapi.json` muss zur laufenden API passen.
  - Integrationstest gegen MariaDB (Testcontainers)
- **frontend:** React 19, TypeScript 6, Vite 8. Der typisierte API-Client wird aus `openapi.json` erzeugt (`openapi-typescript`). Eine Statusseite zeigt die Verbindung zur API.
- **Container:**
  - `deploy/api/Dockerfile`, `deploy/frontend/Dockerfile`, `deploy/proxy/Dockerfile`
  - `compose.yaml` (All-in-One mit Profil `proxy`), `compose.external-proxy.yaml`, `.env.example`
- **nginx/certbot:**
  - gemeinsame Snippets für TLS (TLS 1.2/1.3, BSI-konforme Cipher), Routing und IP-Allowlist
  - Template für den Proxy-Container und Beispiel für externen nginx
  - Übergangszertifikat beim Start und automatischer Wechsel auf das Let's-Encrypt-Zertifikat
  - certbot mit ECDSA P-256 bzw. RSA 3072, Staging-Schalter und automatischem Wechsel von Staging auf Produktion
- **CI** (`.github/workflows/ci.yml): Backend-Build mit Tests, Frontend (Prüfung der API-Typen, Typprüfung, Tests, Build) und ein Compose-Smoketest durch den Proxy inkl. TLS-Protokollprüfung. Dazu Dependabot für Gradle, npm, Docker und Actions.
- **Doku:** README (Schnellstart, Entwicklung), `docs/betrieb.md`, `docs/api/README.md`, `docs/spec-abweichungen.md`, CLAUDE.md (Befehle, Technik), ADR-0004

## Auswirkungen

- REST-API: neuer Endpunkt `GET /api/v1/about`
- Datenbank: Migration V1 (Tabellen `tenant`, `setup_state`)
- Konfiguration: Variablen siehe `.env.example` und `docs/betrieb.md`
- Abweichung von AUFGABE Kap. 7.4: kein OCSP-Stapling, weil Let's Encrypt seit 2025 keine OCSP-Responder mehr betreibt

## Tests

- `./gradlew build`: Unit-Tests für domain und rest-api, ArchUnit, OpenAPI-Abgleich. Der MariaDB-Integrationstest läuft in der CI, lokal nur mit Docker.
- Frontend: `pnpm typecheck`, `pnpm test`, `pnpm build`, `pnpm check:api`
- Compose-Smoketest in der CI
