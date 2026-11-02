# CL-0010: M6 – Verfügbarkeit, Replikat und Online-Abfrage

| | |
|---|---|
| Datum | 2026-11-02 |
| Meilenstein | M6 |
| Bezug | AUFGABE 5.4, D-05, D-13, 8.5; AK S&P „Replikate zur Verfügbarkeitsprüfung“ 1.7 mit Anlagen (Replikat.xsd, Beispiel-XMLs) |

## Motivation
Als Leistungserbringer müssen wir unsere Verfügbarkeiten täglich als Replikat bereitstellen und online abfragbar machen. Als Auftraggeber importieren wir die Replikate der Partner, suchen darin und übernehmen einen Treffer in einen Auftrag (D-05).

## Änderungen

### Teil 1: Modul `replikat`, Fachmodell, XSD 1.7
- Fachmodell `de.openspri.domain.availability`: `Location` (ein Datensatz `ds`), `ProductAvailability` (`pl/p`), `TechnologyAvailability` (`tl/t`), `ChangeMark` (`ak`), `OrderKind` (`auftragsart`). Feldnamen englisch, jedes Feld nennt sein Replikat-Element. `LocationKey` ist die Identität im Delta (SA-016).
- Modul `replikat`:
  - lädt `Anlagen_Replikat_v1.7.zip` beim Build (Prüfsumme, nicht im Repository) und erzeugt daraus die JAXB-Klassen der `Replikat.xsd`
  - `ReplikatXml`: Schemaprüfung; Lesen und Schreiben Datensatz für Datensatz (StAX mit JAXB je `ds`), XML gehärtet
  - `ReplikatFiles`: Dateinamen, ZIP (deflate), MD5, Paket mit Metadatei; `ReplikatMeta` schreibt und liest die Metadatei
- SA-016 (Name des Deltareplikats, Identität eines Datensatzes), SA-017 (vertauschte Koordinaten in den Beispielen)

## Auswirkungen
- S/PRI-Schnittstelle: keine Änderung
- Build: neues Modul `replikat`, Download der Replikat-Anlagen

## Tests
- Teil 1: `ReplikatXmlTest` (die drei Beispiel-XMLs der Anlage 3 sind gültig, lesbar und überstehen Lesen → Schreiben → Prüfen → Lesen unverändert; Felder des Konnektivitätsbeispiels; ungültiges Replikat mit Zeilennummer; Paket mit ZIP, Metadatei und MD5)
