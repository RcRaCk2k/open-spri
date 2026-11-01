# CL-0008: M4 – REST-API, Anmeldung, Setup, Partner, Aufträge

| | |
|---|---|
| Datum | 2026-11-01 (in Arbeit) |
| Meilenstein | M4 |
| Bezug | AUFGABE Kap. 5.5–5.10, 7.3, 7.5, 8.5, 12.1 |

## Motivation

M4 macht open-spri bedienbar: Web-Oberfläche und Fremdsysteme steuern alles über eine dokumentierte REST-API (API-first, Grundsatz 4).

## Änderungen

### Teil 1: Anmeldung und Berechtigungen
- **Neue Module:**
  - `auth-api`: SPI `AuthenticationModule`, `Role`, `Scope`, `Actor`, `AuditLog`
  - `auth-token`: Long-Life-Tokens `spri_<prefix>_<geheimnis>`. Gespeichert wird nur der SHA-256-Hash, verglichen in konstanter Zeit. Ablauf, Widerruf, letzte Nutzung.
  - `auth-local`: Benutzer mit Argon2id-Passwort (mindestens 12 Zeichen). Unbekannte Benutzer kosten dieselbe Zeit wie bekannte.
- **`SecurityConfiguration`:**
  - alle Module als Beans, Sessions per Cookie (SameSite=Strict)
  - CSRF im SPA-Modus (Cookie `XSRF-TOKEN`, Header `X-XSRF-TOKEN`), Bearer-Aufrufe ausgenommen
  - Berechtigungen per `@PreAuthorize` mit `SCOPE_…`
  - öffentlich nur Health, About, OpenAPI/Swagger, Login, Setup, `/spri` und `/replikat` (eigener Schutz)
- **REST:**
  - `POST /auth/login`, `POST /auth/logout`, `GET /auth/me`, `GET /auth/modules`
  - `GET/POST/DELETE /tokens` (Geheimnis nur einmal in der Antwort)
  - `GET/POST/PATCH /users`
- Fehlerantworten nach RFC 9457 (`ProblemHandlers`), Ablehnungen mit `reasons`
- Migration V6: `app_user`, `api_token`, `audit_log`
- **Korrektur:** Die IP-Sperre von `/spri` setzt den Status 403 direkt. `sendError` hätte über `/error` die REST-Sicherheit durchlaufen und 401 geliefert.

## Tests

- `SecurityTest` (6, echtes HTTP): öffentliche Endpunkte, 401 ohne Anmeldung, Token mit Berechtigung und 403 ohne, ungültiges und widerrufenes Token, Berechtigungen nicht über die Rolle hinaus, Session-Login mit CSRF-Schutz (ohne Token 403, mit Token 201)
