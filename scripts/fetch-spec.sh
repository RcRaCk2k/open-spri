#!/usr/bin/env bash
# Laedt die Spezifikationsdokumente des AK S&P (https://ak-spri.de/spri/spri-spezifikationen-2-2/)
# nach docs/spec-original/. Die Dokumente unterliegen dem Urheberrecht des AK und werden
# deshalb nicht im Repository mitgeliefert (siehe docs/AUFGABE-SPRI.md, Kap. 14).
#
# Die Meldungscodetabelle (Anlage 5) existiert nur online im Confluence des AK und muss
# manuell als CSV exportiert werden:
#   https://aksup.atlassian.net/wiki/spaces/SPE/pages/1221034635/SPRI+Meldungscodetabelle
#   -> Ablage als docs/spec-original/SPRI_Meldungscodetabelle.csv
set -euo pipefail

cd "$(dirname "$0")/.."
target="docs/spec-original"
mkdir -p "$target"

base="https://ak-spri.de/wp-content/uploads"
files=(
  "Providervereinbarung_SPRI_V4.4.pdf"
  "Anlage_1_AuftragsMeldugsstruktur_SPRI_V.4.4.xlsx"
  "2018/01/Anlage_2_Technisches_Schema_SPRI_V.4.x.doc"
  "Anlage_3_XSD_SPRI_V.4.x.zip"
  "Anlage_4_Produktbezeichner_SPRI_V.4.X.xlsx"
  "Anlage_6_Produktwechselmatrix_SPRI_V4.X.xlsx"
  "Anlage_7_EPKs_SPRI_V4.4_V.13.pdf"
  "Arbeitshandbuch_SPRI4.X_V2.9.5.pdf"
  "Replikate_zur_Verfuebarkeitspruefung_v1.7.pdf"
  "Anlagen_Replikat_v1.7.zip"
  "Info_Doc_Aggregatorreplikat_V1.2.pdf"
  "Replikat_Aggregator_XSD_XML_1.2.zip"
  "Struktur-und-Semantik-der-LineID_V.1.4.pdf"
  "Info_Doc_Bildungslogik_Home_ID_V.1.0.pdf"
  "Schnittstellenuebergreifend_Security_Anforderungen_ITU_Antrag_V.3.pdf"
  "Testfaelle_SPRIv4X_20250523.xlsx"
)

for f in "${files[@]}"; do
  name="$(basename "$f")"
  if [[ -f "$target/$name" ]]; then
    echo "vorhanden: $name"
    continue
  fi
  echo "lade:      $name"
  curl -sSfL -o "$target/$name" "$base/$f"
done

if [[ ! -f "$target/SPRI_Meldungscodetabelle.csv" ]]; then
  echo "HINWEIS: $target/SPRI_Meldungscodetabelle.csv fehlt (manueller Export aus Confluence, siehe Kopfkommentar)." >&2
fi
