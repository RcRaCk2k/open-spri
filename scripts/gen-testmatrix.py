#!/usr/bin/env python3
"""Erzeugt docs/ANHANG-A-Testfaelle.md aus der KFT-Testfallliste des AK S&P.

Aufruf: python3 scripts/gen-testmatrix.py [Pfad zur Testfaelle_SPRIv4X_*.xlsx]

Benoetigt LibreOffice (soffice) fuer die XLSX->CSV-Konvertierung. Die Scope-Regeln
entsprechen den Entscheidungen D-03, D-04 und D-10 in docs/AUFGABE-SPRI.md.
"""
import collections
import csv
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_XLSX = ROOT / "docs/spec-original/Testfaelle_SPRIv4X_20250523.xlsx"
OUTPUT = ROOT / "docs/ANHANG-A-Testfaelle.md"

# Optionale Testfaelle, die durch unsere Scope-Entscheidung D-04 verpflichtend werden.
SCOPE_FEATURES = ("Dritte", "HomeID", "Konnektivität")

SHEETS = [
    ("Testfälle Partner",
     "Rolle Auftraggeber (EKP/AG) – Blatt „Testfälle Partner“ – KFT-Simulator agiert als Leistungserbringer"),
    ("Testfälle Supplier",
     "Rolle Leistungserbringer (NGA/LE) – Blatt „Testfälle Supplier“ – KFT-Simulator agiert als Auftraggeber"),
]

HEADER = """# Anhang A – Testfall-Matrix (Konformitätstest S/PRI V4.x)

Quelle: `{source}` (AK S&P). Automatisch erzeugt mit `scripts/gen-testmatrix.py`. Inhaltlich gilt immer die Originaldatei.

**Legende Scope:** `PFLICHT` = muss für die Zertifizierung bestanden werden. `PFLICHT (Alternative zu *3)` = einer der mit (*3) markierten TF der Gruppe muss bestanden werden, wir setzen beide um. `PFLICHT (Scope)` = optional laut AK, durch unsere Scope-Entscheidung (Konnektivität, HomeID, Dritte) aber verpflichtend. `EMPFOHLEN` = optional laut AK, wird nach D-10 trotzdem umgesetzt und bestanden.

**Bestandsaufbau (BA = X):** Der TF beginnt mit einem NEU-Auftrag mit Standardkette (QEB, ABM, ERLM, ENTM). Der Testfallname erhält das Suffix `#<externe Auftragsnummer des Bestandsaufbaus>`.

**Fußnoten der Quelle:** (*1) Der Simulator füllt immer alle Attribute, auch optionale (außer Kenner und Besteller). (*2) Alle zulässigen Meldungscodes müssen unterstützt werden. (*3) Einer der aufgeführten TF muss abgearbeitet werden.
"""


def scope(gf: str, obligation: str, description: str) -> str:
    if obligation.startswith("muss"):
        return "PFLICHT"
    if obligation == "":
        return "PFLICHT (Alternative zu *3)"
    if any(k in description for k in SCOPE_FEATURES):
        return "PFLICHT (Scope)"
    return "EMPFOHLEN"


def convert(xlsx: pathlib.Path, workdir: pathlib.Path) -> None:
    subprocess.run(
        ["soffice", "--headless", "--convert-to",
         'csv:Text - txt - csv (StarCalc):44,34,76,1,,0,false,true,false,false,false,-1',
         "--outdir", str(workdir), str(xlsx)],
        check=True, capture_output=True)


def clean(value: str) -> str:
    return value.replace("\n", " ").replace("|", "/").strip()


def main() -> None:
    xlsx = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_XLSX
    out = [HEADER.format(source=xlsx.relative_to(ROOT) if xlsx.is_relative_to(ROOT) else xlsx)]
    summary = []
    with tempfile.TemporaryDirectory() as tmp:
        convert(xlsx, pathlib.Path(tmp))
        for sheet, title in SHEETS:
            csv_file = next(pathlib.Path(tmp).glob(f"*{sheet}.csv"))
            out.append(f"## {title}\n")
            out.append("| TF-ID | GF | Gut/Schlecht | AK | BA | ab Ver. | Beschreibung | Erwartete Meldungsfolge | Hinweise | **Scope** |")
            out.append("|---|---|---|---|---|---|---|---|---|---|")
            gf = ""
            counts = collections.Counter()
            with open(csv_file, encoding="utf-8") as fh:
                for row in list(csv.reader(fh))[1:]:
                    if len(row) < 11:
                        continue
                    if row[2].strip():
                        gf = row[2].split("\n")[0].strip()
                    tf_id = row[3].strip()
                    if not tf_id.startswith(("S.", "Z.")):
                        continue
                    sc = scope(gf, row[5].strip(), row[8])
                    counts[sc if sc == "PFLICHT (Scope)" else sc.split(" (")[0]] += 1
                    out.append(f"| {tf_id} | {gf} | {clean(row[4])} | {clean(row[5]) or '–'} | {clean(row[6])} | "
                               f"{clean(row[7])} | {clean(row[8])} | {clean(row[9])} | {clean(row[10])} | {sc} |")
            summary.append((title.split(" – ")[0], counts))
            out.append("")
    out.append("## Zusammenfassung\n")
    out.append("| Rolle | PFLICHT | PFLICHT (Scope) | EMPFOHLEN | Summe |")
    out.append("|---|---|---|---|---|")
    for role, c in summary:
        out.append(f"| {role} | {c['PFLICHT']} | {c['PFLICHT (Scope)']} | {c['EMPFOHLEN']} | {sum(c.values())} |")
    OUTPUT.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"geschrieben: {OUTPUT}")


if __name__ == "__main__":
    main()
