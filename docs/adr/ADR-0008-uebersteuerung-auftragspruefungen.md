# ADR-0008: Übersteuerung eigener Auftragsprüfungen für KFT-Negativtests

Status: angenommen | Datum: 2026-11-02 | Bezug: AUFGABE 3.3, D-10, R-05; KFT-Testfälle Z.2.2.01, Z.2.2.02, Z.4.2.01, Z.5.2.01; Spezifikation Kap. 2

## Kontext

open-spri prüft eigene Aufträge vor dem Senden streng (Grundsatz 6, `OrderRules`), damit Bedienfehler gar nicht erst beim Partner ankommen. Einige Testfälle des KFT verlangen aber, dass wir als Auftraggeber **absichtlich fehlerhafte** Aufträge senden und die ABBM des Leistungserbringers verarbeiten:

| TF | Fehler | ABBM | eigene Prüfung |
|---|---|---|---|
| Z.2.2.02 (Pflicht), Z.2.2.01 | Kündigungswunschtermin mehr als 179 Tage voraus | 1003 | R-05 |
| Z.5.2.01 | Geschäftsfallart „Diagnose“ bei LAE | 1102 | Geschäftsfallart |
| Z.4.2.01 | „Storno“ statt „Standard“ in einem neuen Auftrag | 1007 | Storno nur zu einem bestehenden Auftrag |

Die 180-Tage-Grenze ist laut Spezifikation (Kap. 2) eine Regel für die Verarbeitung beim Leistungserbringer: Aufträge „können nur prozessiert werden“, wenn der Termin unter 180 Tagen liegt. Das Senden verbietet sie nicht. Ohne Ausweg wäre der Pflicht-TF Z.2.2.02 nicht zu bestehen, und D-10 (alle optionalen TF) nicht erfüllbar.

## Entscheidung

- **Die Prüfungen bleiben Standard.** Wer nichts angibt, bekommt wie bisher 409.
- **Übersteuerung nur einzeln und ausdrücklich:** `POST /orders?send=true&override=…` bzw. `POST /orders/{id}/send?override=…` mit genau diesen Regeln (`RuleOverride`): `R-05`, `GESCHAEFTSFALLART`, `AENDERUNGSKENNZEICHEN`.
- **Eigener Scope `orders:override`:** Er gehört zu keiner Rolle, auch nicht zur Admin-Anmeldung im Frontend. Nur ein Administrator kann ihn ausdrücklich in ein API-Token aufnehmen (`Role.grantable`), zum Beispiel für die Dauer des KFT. Ohne ihn antwortet die API mit 403.
- **Nie übersteuerbar:** XSD, Anlage 1 (Modus `enforce`), Zustandsautomat, Dubletten, R-12 und alle Prüfungen eingehender Nachrichten.
- **Nachvollziehbar:** Jede Übersteuerung steht im Audit-Log (`override=…`) und als Befund „Übersteuert (ADR-0008): …“ im Verlauf des Auftrags.
- Ein neuer Auftrag mit „Storno“ erhält den Zustand `OrderLifecycle.requestWithoutOrder`. Darauf ist nur eine ABBM zulässig, die ihn beendet.

## Alternativen

- **Regeln nur als Warnung:** einfacher, aber ohne Schutz vor Bedienfehlern. Ein Kündigungstermin in 200 Tagen würde unbemerkt gesendet und erst durch die ABBM auffallen.
- **Nicht unterstützen:** Dann wäre der Pflicht-TF Z.2.2.02 im KFT nicht zu bestehen.

## Folgen

- CLAUDE.md nennt die Ausnahme unter „Was nicht erlaubt ist“.
- Die KFT-Szenarien senden die vier Testfälle mit Übersteuerung und einem Token mit `orders:override`. Vor dem echten KFT braucht der Betreiber ein solches Token.
