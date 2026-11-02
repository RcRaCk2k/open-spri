# Verfügbarkeit, Replikat und Online-Abfrage

Grundlage sind AUFGABE 5.4 und die AK-Spezifikation „Replikate zur Verfügbarkeitsprüfung“ 1.7 mit der `Replikat.xsd`. Ein Datensatz beschreibt einen Standort mit den dort verfügbaren Produkten und Technologien. Er enthält keine personenbezogenen Daten.

**JSON-Format:** Ein Datensatz verwendet in der REST-API die Elementnamen der `Replikat.xsd`, so wie Aufträge die der S/PRI-XSD. Listen sind Arrays: `pl` enthält die Produkte (`pl/p`), `tl` die Technologien (`tl/t`), `hidl` die HomeIDs, `zl` die Zeitfenster. Unbekannte Felder sind ein Fehler (400 mit JSON-Pfad); jeder Datensatz wird gegen die `Replikat.xsd` 1.7 geprüft (400 mit `errors`).

```json
{
  "aid": "A-1", "geo": {"long": "13.3143", "lat": "52.5263"},
  "plz": "12345", "o": "Musterstadt", "ot": "Mitte", "s": "Musterstraße", "h": "1", "hz": "a",
  "pl": [{"produkt": "FTTH 100", "technologie": "FTTH", "erwarteteBandbreite": {"downBB": "100", "upBB": "40"}, "bereitstellungszeit": "5"}],
  "tl": [{"technologie": "FTTH", "kont": "Nord", "hidl": ["HID1"], "ausbaustatus": "homesConnected", "auftragsart": "STANDARD", "eigentuemerdaten": false}]
}
```

## Als Leistungserbringer

### Eigene Standorte (`availability:read`, Ändern mit `availability:admin`)

Unsere Verfügbarkeiten liegen in einer eigenen Tabelle (D-13). Gepflegt werden sie per REST oder per Import, eine Anbindung an GIS oder Netzinventar gibt es in v1 nicht.

```bash
curl -s -X POST $SPRI/api/v1/availability/sites -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d @standort.json   # 201
curl -s "$SPRI/api/v1/availability/sites?plz=12345&s=Muster" -H "Authorization: Bearer $TOKEN"     # s und o als Präfix
curl -s -X PUT $SPRI/api/v1/availability/sites/7 -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d @standort.json
curl -s -X DELETE $SPRI/api/v1/availability/sites/7 -H "Authorization: Bearer $TOKEN"              # 204
```

Ein Standort ist eindeutig über die Adress-ID, sonst über die Adresse (`plz`, `o`, `ot`, `s`, `h`, `hz`), jeweils mit Gebäudeteil und HomeIDs (SA-016). Ein zweiter gleicher Standort ergibt 409.

**Import:**

```bash
# Replikat (XML oder ZIP nach Replikat.xsd 1.7): ein Vollreplikat ersetzt den Bestand, ein Deltareplikat wird angewendet
curl -s -X POST "$SPRI/api/v1/availability/sites/import" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/xml" --data-binary @bestand.xml
# CSV, Standorte hinzufügen bzw. ersetzen statt den Bestand zu ersetzen
curl -s -X POST "$SPRI/api/v1/availability/sites/import?mode=merge" -H "Authorization: Bearer $TOKEN" -H "Content-Type: text/csv" --data-binary @standorte.csv
```

```json
{"added": 120, "changed": 3, "deleted": 0, "total": 4711}
```

| `mode` | Wirkung |
|---|---|
| `replace` (Standard) | Die Datei ist der vollständige Bestand. Was nicht darin steht, wird entfernt und erscheint im nächsten Deltareplikat mit DEL. |
| `merge` | Die Standorte der Datei werden hinzugefügt bzw. ersetzt. |
| Deltareplikat (`deltareplikat` = true) | ADD und MOD fügen hinzu bzw. ersetzen, DEL entfernt, UCH wird übergangen. |

Das CSV hat eine Kopfzeile, Trennzeichen `;`, UTF-8 und eine Zeile je Standort und Produkt. Zeilen derselben Adresse werden zu einem Standort zusammengefasst. Spalten heißen wie die Replikat-Elemente: `aid;plz;o;ot;s;h;hz;gebaeudeteil;long;lat;produkt;technologie;downBB;upBB;bereitstellungszeit;ausbaustatus;auftragsart;eigentuemerdaten;kont;hid`. Pflicht sind `plz`, `o`, `s`, `h` und `produkt`; mehrere HomeIDs werden mit `|` getrennt. Ein Fehler ergibt 400 mit Zeilennummern, und es wird nichts geändert.

```csv
plz;o;s;h;produkt;technologie;ausbaustatus;auftragsart;hid
12345;Musterstadt;Musterweg;2;FTTH 100;FTTH;homesPassed;KONNEKTIVITAET;HID7|HID8
12345;Musterstadt;Musterweg;2;FTTH 250;FTTH;homesPassed;KONNEKTIVITAET;HID7|HID8
```

### Replikat für Partner

Ein Partner erhält unser Replikat erst nach einer **Freigabe** (bilaterale Vereinbarung). Darin stehen die optionalen Felder, die er nicht erhält, und die Kontingente (`kont`), auf die er beschränkt ist.

```bash
curl -s -X PUT $SPRI/api/v1/replikat/releases/7 -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"partnerId": 7, "enabled": true, "delta": true, "omittedFields": ["geo", "kont"], "quotas": []}'
curl -s -X POST $SPRI/api/v1/replikat/releases/7/run -H "Authorization: Bearer $TOKEN"     # sofort erzeugen (ersetzt die Dateien des Tages)
curl -s $SPRI/api/v1/replikat/releases/7/files -H "Authorization: Bearer $TOKEN"           # bereitgestellte Dateien
```

Jeden Tag um 03:00 Uhr (`OPENSPRI_REPLIKAT_EXPORT_CRON`) erzeugt open-spri für jede Freigabe:

| Datei | Inhalt |
|---|---|
| `<CarrierCode>_<JJJJMMTT>.zip` | Vollreplikat. Jeder Datensatz hat ein Änderungskennzeichen gegenüber dem vorigen Replikat des Partners (ADD, MOD, UCH), wie es die Spezifikation empfiehlt. |
| `<CarrierCode>_<JJJJMMTT>_delta.zip` | Deltareplikat mit ADD, MOD und DEL gegenüber dem vorigen Replikat; nur wenn `delta` freigegeben ist (SA-016) |
| `…_meta.xml` je Datei | Metadatei: Schemaversion, Dateiname, Format, MD5-Prüfsumme, Bereitstellungszeitpunkt, Carrier Code (Replikat 3.1) |

Jedes Replikat ist eine ZIP-Datei (deflate) mit `<CarrierCode>_<JJJJMMTT>.xml`. Es wird vor der Bereitstellung gegen die `Replikat.xsd` 1.7 geprüft. Der Carrier Code ist der aus den Firmendaten der Ersteinrichtung. open-spri hält die letzten 31 Tage vor.

### Download und Online-Abfrage durch den Partner

Der Partner braucht ein **Partner-Token**: Rolle `PARTNER`, an ihn gebunden, Scope `partner:availability` (siehe [auth.md](auth.md)). Zusätzlich muss er aus einer seiner freigeschalteten IP-Adressen zugreifen, wie beim S/PRI-Endpunkt.

```bash
# Token für Partner 7 ausstellen (Admin)
curl -s -X POST $SPRI/api/v1/tokens -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "Replikat Glasfaser Nord", "role": "PARTNER", "partnerId": 7}'

# Der Partner: Verzeichnis, Metadatei, Replikat (Bearer oder Basic-Auth mit dem Token als Passwort)
curl -s https://spri.example.de/replikat/ -H "Authorization: Bearer $PARTNER_TOKEN"
curl -s -u "gfnord:$PARTNER_TOKEN" https://spri.example.de/replikat/TSTNZ_20261102_meta.xml
curl -s -u "gfnord:$PARTNER_TOKEN" https://spri.example.de/replikat/TSTNZ_20261102.zip -o TSTNZ_20261102.zip

# Online-Abfrage: mindestens plz und s, oder aid, oder hid
curl -s "https://spri.example.de/api/v1/availability?plz=12345&s=Musterstraße&h=1" -H "Authorization: Bearer $PARTNER_TOKEN"
```

Die Online-Abfrage antwortet mit einer Liste von Datensätzen im JSON-Format oben, mit denselben Feldern und Kontingenten wie das Replikat des Partners. Der AK hat für eine Online-Abfrage keinen Standard festgelegt (OP-05). Unser Format ist deshalb als Vorschlag dokumentiert (SA-003).

| Status | Bedeutung |
|---|---|
| 401 | kein Token |
| 403 | kein Partner-Token, Partner deaktiviert, Quelladresse nicht freigeschaltet oder keine Freigabe |
| 404 | Datei existiert nicht (mehr) |

Die Replikat-Spezifikation verlangt beim Download zusätzlich ein Client-Zertifikat. Das lässt sich am Reverse-Proxy einrichten (SA-018, Vorlage mit M8).

## Als Auftraggeber

### Quellen (`availability:read`, Ändern mit `availability:admin`)

Je Partner gibt es eine oder mehrere Quellen:

| `kind` | `url` | Verhalten |
|---|---|---|
| `REPLIKAT` | Download-Verzeichnis des Partners, z. B. `https://spri.partner.example/replikat/` | täglicher Import, Suche im lokalen Bestand |
| `ONLINE` | Basis-URL einer anderen open-spri-Instanz | jede Suche fragt `GET <url>/api/v1/availability` |

Zugangsdaten: Mit `username` wird Basic-Auth gesendet, ohne `username` geht das `secret` als Bearer-Token. Bei einer open-spri-Instanz ist das ein Partner-Token, das der Partner für uns ausgestellt hat. Das Geheimnis wird mit `OPENSPRI_MASTER_KEY` verschlüsselt gespeichert und nie zurückgegeben (`hasSecret`). Ohne Master-Key antwortet die API mit 409.

```bash
# Replikat-Quelle mit Deltareplikat
curl -s -X POST $SPRI/api/v1/replikat/sources -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"partnerId": 7, "kind": "REPLIKAT", "url": "https://spri.partner.example/replikat/", "username": "testnetz", "secret": "'$PARTNER_TOKEN'", "delta": true}'

# Online-Abfrage an eine open-spri-Instanz
curl -s -X POST $SPRI/api/v1/replikat/sources -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"partnerId": 8, "kind": "ONLINE", "url": "https://spri.partner2.example", "secret": "'$PARTNER2_TOKEN'"}'

# Ändern ohne secret behält das hinterlegte Geheimnis; löschen
curl -s -X PUT $SPRI/api/v1/replikat/sources/1 -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"partnerId": 7, "kind": "REPLIKAT", "url": "https://spri.partner.example/replikat/", "username": "testnetz", "delta": true, "enabled": false}'
curl -s -X DELETE $SPRI/api/v1/replikat/sources/2 -H "Authorization: Bearer $TOKEN"
```

### Import

Der Import läuft täglich um 07:00 (`OPENSPRI_REPLIKAT_IMPORT_CRON`), nachdem die Partner ihre Replikate bis 06:00 bereitgestellt haben (Replikat 3.1). Ablauf je Quelle:

1. Metadatei `<CarrierCode>_<JJJJMMTT>_meta.xml` von heute laden, sonst die von gestern. Der Carrier Code ist der aus den Partnerdaten.
2. Ist das Replikat dieses Tages schon importiert, passiert nichts (Ergebnis mit Hinweis „bereits importiert“). Ein Deltareplikat wird also nie zweimal angewendet.
3. Ein Deltareplikat (`<CC>_<JJJJMMTT>_delta.zip`, SA-016) wird nur genommen, wenn `delta` gesetzt ist und der Vortag erfolgreich importiert wurde. Sonst wird das Vollreplikat importiert.
4. Die Datei wird geladen und ihre MD5 mit der Metadatei verglichen. Danach wird sie gegen die `Replikat.xsd` 1.7 geprüft (höchstens 1 GiB entpackt).
5. Ein Vollreplikat ersetzt den Bestand des Partners. Ein Delta wird nach `ak` angewendet: ADD und MOD ersetzen, DEL löscht.

Schlägt ein Schritt fehl, bleibt der Bestand unverändert und der Lauf steht mit `FAILED` und Grund in der Liste. Warnungen ändern nichts am Import, zum Beispiel ein abweichender Carrier Code oder Koordinaten außerhalb Deutschlands (vertauscht, SA-017).

```bash
curl -s -X POST $SPRI/api/v1/replikat/sources/1/run -H "Authorization: Bearer $TOKEN"     # jetzt importieren
curl -s "$SPRI/api/v1/replikat/sources/1/imports?limit=10" -H "Authorization: Bearer $TOKEN"
```

```json
{"id": 12, "status": "OK", "fileName": "GFNORD_20261102_delta.zip", "replikatDate": "2026-11-02", "delta": true,
 "records": 3, "added": 1, "changed": 1, "deleted": 1, "warnings": [], "error": null, "startedAt": "…", "finishedAt": "…"}
```

### Suche

`GET /api/v1/availability/search` sucht über alle Partner mit aktiver Quelle. Bei Replikat-Quellen wird im importierten Bestand gesucht, bei Online-Quellen wird die Instanz des Partners gefragt. Mögliche Kriterien:

- Adresse: `plz`, `o`, `s`, `h`, `hz`; `o` und `s` gelten als Präfix
- `hid` (HomeID) oder `aid` (Adress-ID)
- Koordinaten: `lat`, `long`, `radius` in Metern (Standard 50, höchstens 5000), nur bei Replikat-Quellen

Mit `partnerId` wird nur bei diesem Partner gesucht. Ein Partner, der nicht antwortet, verhindert die Antwort nicht: Er steht mit dem Grund in `errors`.

```bash
curl -s "$SPRI/api/v1/availability/search?plz=12345&s=Muster&h=1" -H "Authorization: Bearer $TOKEN"
curl -s "$SPRI/api/v1/availability/search?lat=52.5263&long=13.3143&radius=100" -H "Authorization: Bearer $TOKEN"
```

```json
{"hits": [{"partnerId": 7, "partnerName": "Glasfaser Nord", "source": "REPLIKAT", "ds": {"plz": "12345", "s": "Musterstraße", "h": "1", "pl": [ … ], "tl": [ … ]}}],
 "errors": {"8": "HTTP 503 von https://spri.partner2.example/api/v1/availability?…"}}
```

### Treffer in einen Auftrag übernehmen

`POST /api/v1/availability/order-template` erzeugt aus einem Treffer einen vorbefüllten NEU-Auftrag. Die Abbildung folgt Kapitel 4 der Replikat-Spezifikation:

| Replikat | Auftrag (`geschaeftsfall`) |
|---|---|
| `plz`, `o`, `ot`, `s`, `h`, `hz` | `auftragsposition/geschaeftsfallProdukt/standortA` |
| `gebaeudeteil` | `standortA/gebaeudeteil/gebaeudeteilName` |
| HomeID (`hidl`) | `geschaeftsfallProdukt/vormieter/homeId/homeIdNummer` |
| erstes gewähltes Produkt | `auftragsposition/produkt/bezeichner` |
| weitere Produkte | je eine `auftragsposition/position` |
| `auftragsart` = `KONNEKTIVITAET` | zusätzliche Position „Konnektivität“ |
| `zl` (Zeitfenster) | `termine/auftraggeberWunschtermin/zeitfenster` (nur S/PRI-Werte 1 bis 20) |

Produkte und HomeID müssen zum Standort gehören, sonst kommt 400. Bedient der Standort mehrere HomeIDs und ist keine gewählt, steht das in `missing`. `missing` nennt außerdem alles, was vor `POST /orders` noch fehlt, etwa die externe Auftragsnummer, den Endkunden und den Wunschtermin. `hints` gibt Hinweise aus dem Replikat weiter, zum Beispiel, dass Eigentümerdaten verlangt werden.

```bash
curl -s -X POST $SPRI/api/v1/availability/order-template -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"partnerId": 7, "ds": { … Treffer … }, "products": ["FTTH 100", "Feste IP-Adresse"], "hid": "HID1"}'
```
