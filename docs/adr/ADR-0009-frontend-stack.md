# ADR-0009: Frontend mit Mantine, TanStack Query, React Router und Playwright

Status: angenommen | Datum: 2026-11-02 | Bezug: AUFGABE 8.6, 9, 10.1 Punkt 9; Grundsatz 4; ADR-0004

## Kontext

AUFGABE 8.6 legt React, TypeScript strict, TanStack Query, React Router, `react-hook-form` und `zod` fest. Das Komponenten-Framework überlässt sie dem Entwickler (Vorschlag: Mantine oder MUI). Das Frontend soll barrierearm sein (Ziel WCAG 2.1 AA), deutsch, und ausschließlich die öffentliche REST-API `/api/v1` nutzen (Grundsatz 4). Abnahmekriterium von M7 ist eine grüne Playwright-E2E-Suite.

## Entscheidung

- **Komponenten: Mantine 9** (`@mantine/core`, `@mantine/hooks`, `@mantine/notifications`).
  - Die Styles sind statisches CSS ohne CSS-in-JS zur Laufzeit.
  - Die Komponenten bringen Tastaturbedienung und ARIA-Attribute mit, etwa Stepper, Modal, Tabs, Combobox und AppShell.
  - Sie decken alle geplanten Seiten ab, ohne dass weitere UI-Bibliotheken nötig werden.
- **Theme:** Teal. Damit WCAG 2.1 AA erfüllt ist, sind drei Werte dunkler als Mantines Standard (`src/theme.ts`):
  - die Primärfarbe (Stufe 9)
  - die Farbe für Nebentexte („dimmed“)
  - die helle Variante
- **Daten:**
  - **TanStack Query 5** für alle API-Aufrufe.
  - Der Client kommt aus `openapi-fetch` mit den aus `docs/api/openapi.json` erzeugten Typen. Pfade sind typisiert, handgeschriebene URLs gibt es nicht.
  - Fehler sind `ApiError` mit RFC-9457-Inhalt.
  - Eine 401 auf einen beliebigen Aufruf beendet die Sitzung im Frontend, und die Anmeldung erscheint.
- **Routing:** React Router 8 im Bibliotheksmodus (`createBrowserRouter`), ohne Framework-Modus und ohne Server-Rendering. Das Frontend bleibt eine statische Seite hinter nginx.
- **Formulare:** `react-hook-form` mit `zod` 4. Die API prüft alles erneut, die Prüfung im Browser dient nur der Bedienung.
- **Anmeldung:**
  - Das Frontend nutzt nur die Session (`POST /auth/login`) mit dem CSRF-Cookie `XSRF-TOKEN`.
  - API-Tokens werden im Browser nie gespeichert.
  - Die Navigation zeigt nur, was die Scopes der Anmeldung erlauben. Die Berechtigung prüft weiterhin die API.
- **Tests:**
  - Vitest mit Testing Library (jsdom) für Logik und Komponenten.
  - **Playwright** (Chromium) mit **axe-core** für die E2E-Suite gegen einen laufenden Stack: in der CI der komplette Compose-Stack hinter dem Proxy, lokal `pnpm dev` mit der API.
  - Ein E2E-Test besucht jede Seite der Navigation und schlägt fehl, wenn ein Aufruf nicht an `/api/v1` geht oder axe einen WCAG-2.1-AA-Verstoß findet, im hellen wie im dunklen Farbschema.

## Alternativen

- **MUI:** Es ist ebenso vollständig, setzt aber auf Emotion (CSS-in-JS zur Laufzeit), ist größer und folgt Material Design. Das passt schlecht zu einer schlichten Fachanwendung.
- **Komponenten selbst bauen (etwa Radix plus eigenes CSS):** Damit hätten wir mehr Kontrolle, aber deutlich mehr Aufwand für Barrierefreiheit und Pflege.
- **Cypress statt Playwright:** AUFGABE 9 nennt Playwright. Dort laufen Tests auch gegen HTTPS mit selbstsigniertem Zertifikat ohne Umwege.

## Konsequenzen

- Neue Seiten verwenden Mantine-Komponenten und die Hilfen in `src/components`. Eigenes CSS kommt nur dorthin, wo Mantine keine Eigenschaft anbietet (`src/styles.css`).
- Neue Seiten kommen in `src/navigation.ts` mit dem nötigen Scope. Der E2E-Test prüft sie dann automatisch auf API-Nutzung und Barrierefreiheit.
- Versionen sind in `frontend/package.json` exakt festgelegt.
