# Meldungscodes (Anlage 5)

Die Meldungscodetabelle des AK S&P ist urheberrechtlich geschützt und kein Teil von open-spri. Der Betreiber exportiert sie aus dem AK-Confluence und importiert sie (beim Start über `OPENSPRI_MESSAGE_CODES_FILE` oder per REST). Ohne Tabelle lehnt open-spri jede eigene Meldung ab, weil kein Code geprüft werden kann (R-13).

## Anzeigen (`orders:read`)

```bash
curl -s "$SPRI/api/v1/message-codes?messageType=ABBM" -H "Authorization: Bearer $TOKEN"
```

```json
{
  "source": "SPRI_Meldungscodetabelle.csv",
  "sha256": "9c1e…",
  "codes": [
    {"code": "1010", "text": "…", "messageTypes": ["ABBM"], "businessCases": [], "localAddition": false}
  ]
}
```

`businessCases` leer bedeutet: für alle Geschäftsfälle. `localAddition: true` kennzeichnet Codes, die open-spri ergänzt, weil sie in der AK-Tabelle fehlen (siehe `docs/spec-abweichungen.md`).

## Neue Version importieren (`message-codes:admin`)

```bash
curl -s -X POST "$SPRI/api/v1/message-codes/import?filename=SPRI_Meldungscodetabelle.csv" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/octet-stream" \
  --data-binary @SPRI_Meldungscodetabelle.csv
```

Die Datei wird unverändert angenommen (UTF-16-TSV aus Confluence oder CSV), versioniert gespeichert und sofort aktiv. Die Antwort ist die neue Tabelle wie bei `GET`. Bei mehreren Instanzen übernehmen die anderen die neue Version beim nächsten Neustart.
