# REST-API von open-spri

Die REST-API ist die **einzige** Programmierschnittstelle von open-spri. Die Web-Oberfläche nutzt genau diese Aufrufe, und externe Systeme (CRM, OSS, Workflow) haben denselben Funktionsumfang.

- Maschinenlesbare Beschreibung: [`openapi.json`](openapi.json) (OpenAPI 3.1, wird aus dem Code erzeugt und in der CI geprüft)
- Interaktiv: `https://<host>/api/docs` (Swagger UI)
- Basis-Pfad: `/api/v1`

## Inhalt

| Seite | Thema |
|---|---|
| [setup.md](setup.md) | Ersteinrichtung mit dem Setup-Token |
| [auth.md](auth.md) | Anmeldung, Tokens, Benutzer, Rollen und Scopes |
| [partner.md](partner.md) | Partner, Zertifikate, IP-Freigaben, WSDL, Onboarding-Blatt |
| [auftraege.md](auftraege.md) | Aufträge in allen Rollen, Storno, Terminverschiebung, Reklamation, Meldungen |
| [zustellung.md](zustellung.md) | Postausgang, Eingang, Nachrichtenarchiv |
| [ereignisse.md](ereignisse.md) | Event-Feed, Webhooks mit HMAC-Signatur, Idempotenz |
| [meldungscodes.md](meldungscodes.md) | Meldungscodetabelle anzeigen und importieren |
| [ablaeufe.md](ablaeufe.md) | Ende-zu-Ende: NEU als Auftraggeber, eingehenden Auftrag als Leistungserbringer bearbeiten |

Die Beispiele verwenden `https://spri.example.de` und ein Token in der Variablen `$TOKEN`:

```bash
export SPRI=https://spri.example.de
export TOKEN=spri_Ab3dE7fG_…   # aus POST /api/v1/tokens, siehe auth.md
```

## Konventionen

| Thema | Regel |
|---|---|
| Format | JSON (UTF-8), Feldnamen in camelCase. S/PRI-Fachbegriffe bleiben wie im Standard (`QEB`, `ABM` …). Aufträge und Meldungsattribute verwenden die Feldnamen der AK-XSD ([auftraege.md](auftraege.md#json-format)). |
| Fehler | RFC 9457 „Problem Details“ (`application/problem+json`), siehe unten |
| Anmeldung | `Authorization: Bearer spri_…` (Long-Life-Token) oder Session der Web-Oberfläche mit CSRF-Schutz |
| Berechtigung | Scopes je Endpunkt (z. B. `orders:write`), siehe [auth.md](auth.md#rollen-und-scopes). Fehlt ein Scope: 403. |
| Idempotenz | Header `Idempotency-Key` bei allen POSTs, die eine S/PRI-Nachricht auslösen (`/orders…`); empfohlen für jedes Fremdsystem ([ereignisse.md](ereignisse.md#idempotenz)) |
| Paginierung | Listen sind nach `id` absteigend sortiert. Nächste Seite mit `?cursor=<kleinste id der Seite>&limit=…`. Der Event-Feed läuft aufsteigend mit `?after=<größte id>`. |
| Asynchron | Senden heißt: geprüft und in den Postausgang gelegt (202 Accepted). Ob der Partner quittiert hat, zeigen `GET /deliveries?orderId=…` und das Ereignis `delivery.updated`. |
| Versionierung | Pfad `/api/v1`. Inkompatible Änderungen nur mit `/api/v2`. |

### Fehlerantworten

| Status | Wann | Zusatzfelder |
|---|---|---|
| 400 | Anfrage oder S/PRI-Inhalt ungültig (JSON, XSD, Anlage 1) | `errors`: Liste mit JSON-Pfad bzw. XSD-/Anlage-1-Befund |
| 401 | nicht angemeldet, Token unbekannt, abgelaufen oder widerrufen | |
| 403 | Scope fehlt | |
| 404 | Objekt existiert nicht | |
| 409 | fachlich nicht zulässig (Zustandsautomat, Dublette, R-xx-Regel) | `reasons` (mit Regelverweis), `allowedActions` (was jetzt zulässig ist) |
| 422 | `Idempotency-Key` für eine andere Anfrage verwendet | |

```json
{
  "type": "about:blank",
  "title": "Aktion nicht zulässig",
  "status": 409,
  "detail": "Die Aktion ist nicht zulässig",
  "instance": "/api/v1/orders/42/messages",
  "reasons": ["ERLM ist in Phase ACKNOWLEDGED nicht zulässig (7.2)"],
  "allowedActions": [
    {"kind": "MESSAGE", "messageType": "ABM", "changeIndicator": "Standard", "request": null},
    {"kind": "MESSAGE", "messageType": "ABBM", "changeIndicator": "Standard", "request": null}
  ]
}
```

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
