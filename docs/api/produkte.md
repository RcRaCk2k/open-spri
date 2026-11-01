# Produkte und Produktwechselmatrix

Lesen mit `partners:read`, Ändern mit `partners:admin`.

## Produktkatalog

Anlage 4 nennt nur Beispiele für Produktbezeichner und ist nicht normativ. Deshalb pflegt jeder Betreiber seinen Katalog selbst (AUFGABE 5.9).

| Feld | Bedeutung |
|---|---|
| `partnerId` | leer = gilt für alle Partner |
| `role` | `LEISTUNGSERBRINGER`: unser Produkt, das Partner bestellen; `AUFTRAGGEBER`: Produkt des Partners, das wir bestellen |
| `identifier` | Produktbezeichner wie im Auftrag (`produkt/bezeichner`) |
| `productGroup`, `description` | z. B. `FTTH` |
| `active` | inaktive Produkte zählen bei der Prüfung nicht |

```bash
curl -s -X POST $SPRI/api/v1/products -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"role": "LEISTUNGSERBRINGER", "identifier": "FTTH 100", "productGroup": "FTTH"}'      # 201
curl -s "$SPRI/api/v1/products?partnerId=7&role=LEISTUNGSERBRINGER" -H "Authorization: Bearer $TOKEN"
curl -s -X PUT $SPRI/api/v1/products/5 -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"role": "LEISTUNGSERBRINGER", "identifier": "FTTH 100", "active": false}'
curl -s -X DELETE $SPRI/api/v1/products/5 -H "Authorization: Bearer $TOKEN"                 # 204
```

`GET /products?partnerId=7` liefert die Produkte des Partners **und** die für alle Partner. Sobald für einen Partner ein aktives Produkt mit `role=LEISTUNGSERBRINGER` gilt, bekommt ein eingehender Auftrag mit einem anderen Bezeichner den Befund „Produkt … ist nicht im Produktkatalog“. Die Position „Konnektivität“ (5.1.2) ist davon ausgenommen. Groß- und Kleinschreibung zählt nicht.

## Produktwechselmatrix (Anlage 6)

Die Matrix sagt, mit welchem Geschäftsfall man von einem Bestandsprodukt zu einem Zielprodukt wechselt: `LAE` (Leistungsänderung) oder `AEN-LMAE` (Leistungsmerkmaländerung). Fehlt ein Wechsel, geht er nur mit NEU und KUE-AG (Hinweis in Anlage 6).

Es gibt eine **Vorgabe für alle Partner** (ohne `partnerId`) und je Partner eine **eigene Matrix**. Hat ein Partner eine eigene Matrix, gilt sie vollständig anstelle der Vorgabe.

```bash
# aus dem CSV-Export der Tabelle in Anlage 6 importieren (ersetzt die Matrix)
curl -s -X POST $SPRI/api/v1/product-change-matrix/import -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: text/csv" --data-binary @anlage6.csv

# als JSON ersetzen; eine leere Liste löscht die eigene Matrix des Partners
curl -s -X PUT "$SPRI/api/v1/product-change-matrix?partnerId=7" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '[{"from": "FTTH 25", "to": "FTTH 50", "businessCase": "LAE"}]'

curl -s "$SPRI/api/v1/product-change-matrix?partnerId=7" -H "Authorization: Bearer $TOKEN"
curl -s "$SPRI/api/v1/product-change-matrix/lookup?partnerId=7&from=FTTH%2025&to=FTTH%2050" -H "Authorization: Bearer $TOKEN"
```

```json
{"from": "FTTH 25", "to": "FTTH 50", "businessCase": "LAE", "source": "PARTNER"}
```

`source` ist `PARTNER`, `DEFAULT` oder `NONE` (kein Wechsel mit LAE/AEN-LMAE möglich).

**Import-Format:** eine Kopfzeile, deren erste Zelle leer ist, mit den Zielprodukten; danach je Bestandsprodukt eine Zeile mit `LAE`, `AEN-LMAE` oder leer. Trennzeichen `,` oder `;`. Titel, Legende und Hinweise vor und nach der Tabelle werden übersprungen. Anlage 6 schreibt die Ziele technologieneutral (`FTTx 50`), weil ein Wechsel nur innerhalb einer Basistechnologie möglich ist. Beim Import wird `FTTx` deshalb durch die Technologie des Bestandsprodukts ersetzt: `FTTB 25` → `FTTx 50` wird zu `FTTB 25` → `FTTB 50` (SA-013). Ein unbekannter Geschäftsfall in einer Zelle ergibt 400 mit der Zeilennummer.

Die AK-Anlage selbst liegt nicht im Repository (Urheberrecht). `scripts/fetch-spec.sh` lädt sie als Excel-Datei nach `docs/spec-original/`; das Tabellenblatt der Matrix wird als CSV gespeichert, zum Beispiel mit `libreoffice --headless --convert-to csv Anlage_6_Produktwechselmatrix_SPRI_V4.X.xlsx`.

Die Matrix wird derzeit nur gepflegt und abgefragt. Die Prüfung eines LAE- oder AEN-LMAE-Auftrags gegen den Bestand kommt mit dem Bestand (M5).
