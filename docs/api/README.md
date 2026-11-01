# REST-API von open-spri

Die REST-API ist die **einzige** Programmierschnittstelle von open-spri. Die Web-Oberfläche nutzt genau diese Aufrufe, und externe Systeme (CRM, OSS, Workflow) haben denselben Funktionsumfang.

- Maschinenlesbare Beschreibung: [`openapi.json`](openapi.json) (OpenAPI 3.1, wird aus dem Code erzeugt und in der CI geprüft)
- Interaktiv: `https://<host>/api/docs` (Swagger UI)
- Basis-Pfad: `/api/v1`

> Stand M0: Es gibt bisher nur `GET /api/v1/about`. Mit M4 kommen Anmeldung, Tokens, Idempotenz, Fehlerformat, Paginierung, Webhooks und alle fachlichen Endpunkte hinzu, jeweils mit Beispielen auf eigenen Seiten in diesem Verzeichnis.

## Konventionen (verbindlich ab M4)

| Thema | Regel |
|---|---|
| Format | JSON (UTF-8), Feldnamen in camelCase. S/PRI-Fachbegriffe bleiben wie im Standard (`QEB`, `ABM` …). |
| Fehler | RFC 9457 „Problem Details“ (`application/problem+json`) |
| Anmeldung | `Authorization: Bearer spri_…` (Long-Life-Token) oder Session der Web-Oberfläche |
| Idempotenz | `Idempotency-Key`-Header bei allen POSTs, die eine S/PRI-Nachricht auslösen |
| Paginierung | Cursor-basiert (`?cursor=…&limit=…`) |
| Versionierung | Pfad `/api/v1`. Inkompatible Änderungen nur mit `/api/v2`. |

## Betrieb

### `GET /api/v1/about`: Version und Build

Ohne Anmeldung aufrufbar.

```bash
curl -s https://spri.example.de/api/v1/about
```

```json
{
  "name": "open-spri",
  "version": "1.0.0",
  "spriVersion": "4.4",
  "buildDigest": "sha256:3f1c…",
  "certifiedBuild": true
}
```

| Feld | Bedeutung |
|---|---|
| `version` | Softwareversion (SemVer) |
| `spriVersion` | implementierte S/PRI-Version |
| `buildDigest` | Digest des laufenden Images, falls beim Start gesetzt (`OPENSPRI_BUILD_DIGEST`) |
| `certifiedBuild` | `true`, wenn der Digest zu einem Build gehört, der den Konformitätstest bestanden hat |

### `GET /health`: Zustand

```bash
curl -s https://spri.example.de/health
# {"status":"UP"}
```

`/health/liveness` und `/health/readiness` sind für Container-Orchestrierung gedacht.
