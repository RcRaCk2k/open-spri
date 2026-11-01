# ADR-0006: Attributprüfung nach Anlage 1 – generierte Regeln, Modi enforce/report

Status: angenommen | Datum: 2026-11-01 | Bezug: AUFGABE Kap. 5.3 (Prüfschritt 4), Spezifikation 10.4 (4)/(5)

## Kontext

Anlage 1 legt für jedes Attribut fest, ob es je Geschäftsfall und Produktgruppe (Aufträge) bzw. je Meldungstyp (Meldungen) Pflicht, optional oder nicht zulässig ist. Das geht über die XSD hinaus. Anlage 1 ist eine Excel-Datei mit deutschen Bezeichnungen und steht unter dem Urheberrecht des AK. Einige Regeln stehen nur als Freitext in der Spalte „Anmerkung“, zum Beispiel „nur bei Terminverschiebung, dann Pflicht“.

## Entscheidung

- **Regeln werden zur Build-Zeit generiert** (Modul `spri-rules`, Quellset `generator`):
  - Anlage 1 wird mit Prüfsumme geladen.
  - Jede Zeile wird über die JAXB-Klassen der XSD einem XML-Pfad zugeordnet.
  - Das Ergebnis ist `anlage1-rules.tsv` mit Zeilennummer, Bezeichnungspfad, XML-Pfad und Kardinalität je Spalte.
  - Zur Laufzeit wird keine Excel-Bibliothek gebraucht.
- **Abweichungen bei der Zuordnung** stehen in `anlage1-mapping.txt`, kommentiert und versioniert. Der Build bricht ab, wenn eine Zeile nicht aufgelöst wird oder ein Eintrag unbenutzt ist.
- **Freitext-Bedingungen** werden strukturiert in `anlage1-conditions.txt` abgebildet, jeweils mit zitierter Quelle: `only GF`, `not GF`, `only AKZ`, `choice`.
- **XSD-Choices** werden als Alternativgruppe `a|b` geprüft. Die Kardinalität der choice-Zeile gilt für „eine der Alternativen“, Felder nur für die Alternativen, die sie laut XSD haben.
- **Unbekannte Produktgruppe:** Ein Verstoß wird nur gemeldet, wenn er in allen in Frage kommenden Spalten besteht.
- **Modi** (`openspri.soap.attribute-rules.*`):
  - **ausgehend `enforce`**: Eigene Nachrichten mit Verstoß werden nicht gesendet.
  - **eingehend `report`**: Verstöße werden protokolliert und im Archiv vermerkt, die Nachricht wird angenommen. Mit `enforce` antwortet die Software mit TEQ 0995.

## Begründung der Standardwerte

Die Auslegung der Freitext-Bedingungen ist unsere Interpretation. Würden wir eingehend streng prüfen, könnte ein Fehler in unserer Auslegung gültige Nachrichten von Partnern oder vom KFT-Simulator abweisen. Für den Leistungserbringer wäre das teurer als eine zu tolerante Annahme. Eigene Nachrichten prüfen wir dagegen streng: Was wir senden, muss Anlage 1 erfüllen.

## Konsequenzen

- Wechselt der Standardwert eingehend auf `enforce`, braucht es ein neues ADR, sobald die Regeln im KFT bestätigt sind.
- Bedingungen, die vom Wert anderer Felder abhängen (z. B. „Telefonnummer nur wenn KontaktaufnahmeErwuenscht = ja“), werden nicht geprüft (SA-009).
