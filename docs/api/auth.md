# Anmeldung, Tokens, Benutzer

open-spri kennt zwei Arten der Anmeldung. Weitere Verfahren (OIDC, SAML, LDAP) kommen als Modul über die SPI `AuthenticationModule` dazu (Grundsatz 5).

| Verfahren | Für wen | Wie |
|---|---|---|
| **API-Token** (`api-token`) | Fremdsysteme, Skripte | `Authorization: Bearer spri_<prefix>_<geheimnis>` |
| **Benutzer und Passwort** (`local-users`) | Web-Oberfläche | `POST /api/v1/auth/login`, danach Session-Cookie mit CSRF-Schutz |

`GET /api/v1/auth/modules` listet die aktiven Verfahren, `GET /api/v1/auth/me` zeigt die aktuelle Anmeldung und ihre Scopes:

```bash
curl -s $SPRI/api/v1/auth/me -H "Authorization: Bearer $TOKEN"
```

```json
{"kind": "TOKEN", "name": "CRM Produktion", "scopes": ["deliveries:read", "events:read", "orders:read", "orders:write"]}
```

## API-Tokens

Tokens sind langlebig und gehören zu einer Rolle. Optional lassen sie sich auf einen Teil der Scopes der Rolle beschränken und mit einem Ablaufdatum versehen. Gespeichert wird nur der SHA-256-Hash. Das Geheimnis erscheint **nur einmal** in der Antwort.

### Token anlegen (`users:admin`)

```bash
curl -s -X POST $SPRI/api/v1/tokens -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "CRM Produktion", "role": "INTEGRATION", "scopes": ["orders:read", "orders:write", "events:read"], "expiresAt": "2027-12-31T23:59:59Z"}'
```

```json
{
  "token": "spri_Ab3dE7fG_kq2…",
  "details": {
    "id": 3, "name": "CRM Produktion", "prefix": "Ab3dE7fG", "role": "INTEGRATION",
    "scopes": ["events:read", "orders:read", "orders:write"],
    "createdAt": "2026-11-01T10:00:00Z", "createdBy": "user:admin",
    "expiresAt": "2027-12-31T23:59:59Z", "lastUsedAt": null, "lastUsedIp": null, "revokedAt": null
  }
}
```

Der `prefix` ist öffentlich und taucht in Logs und im Audit-Log auf, das Geheimnis nie.

### Auflisten und widerrufen

```bash
curl -s $SPRI/api/v1/tokens -H "Authorization: Bearer $ADMIN_TOKEN"
curl -s -X DELETE $SPRI/api/v1/tokens/3 -H "Authorization: Bearer $ADMIN_TOKEN"   # 204
```

Ein widerrufenes oder abgelaufenes Token führt zu 401.

## Benutzer (`users:admin`)

```bash
curl -s -X POST $SPRI/api/v1/users -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"username": "m.muster", "displayName": "Max Muster", "role": "OPERATOR_LE", "password": "mindestens-zwoelf-zeichen"}'

curl -s -X PATCH $SPRI/api/v1/users/2 -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"enabled": false}'
```

Passwörter werden mit Argon2id gespeichert (mindestens 12 Zeichen).

## Anmelden mit Session (Web-Oberfläche)

```bash
curl -s -c jar -X POST $SPRI/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "ein-sehr-langes-passwort"}'
# ändernde Aufrufe brauchen danach das Cookie XSRF-TOKEN im Header X-XSRF-TOKEN
curl -s -b jar -X POST $SPRI/api/v1/auth/logout -H "X-XSRF-TOKEN: $(awk '/XSRF-TOKEN/ {print $7}' jar)"
```

## Rollen und Scopes

| Scope | Erlaubt |
|---|---|
| `orders:read` | Aufträge, Verlauf, Archiv, Meldungscodes lesen |
| `orders:write` | Aufträge senden, Storno, Terminverschiebung, Reklamation, Meldungen als Auftraggeber und abgebender EKP |
| `le:messages:write` | Meldungen als Leistungserbringer (QEB, ABM, TAM, ERLM, …) |
| `deliveries:read` / `deliveries:write` | Postausgang und Eingang lesen bzw. eingreifen |
| `partners:read` / `partners:admin` | Partner lesen bzw. verwalten |
| `availability:read` | Verfügbarkeit (ab M6) |
| `events:read` | Event-Feed |
| `webhooks:admin` | Webhooks verwalten |
| `message-codes:admin` | Meldungscodetabelle importieren |
| `users:admin` | Benutzer und Tokens |
| `settings:admin` | Einstellungen, Signaturschlüssel |
| `audit:read` | Audit-Log |

| Rolle | Scopes |
|---|---|
| `ADMIN` | alle |
| `OPERATOR_AG` | `orders:read`, `orders:write`, `deliveries:read/write`, `partners:read`, `availability:read`, `events:read` |
| `OPERATOR_LE` | wie `OPERATOR_AG`, aber `le:messages:write` statt `orders:write` |
| `VIEWER` | nur lesen, dazu `audit:read` |
| `INTEGRATION` | für Fremdsysteme: `orders:read/write`, `le:messages:write`, `deliveries:read`, `partners:read`, `availability:read`, `events:read` |

Jede ändernde Aktion steht mit Akteur (`user:…` bzw. `token:…`) im Audit-Log, ebenso jeder Zugriff auf Rohnachrichten.
