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
