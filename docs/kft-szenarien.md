# KFT-Szenarien und Simulator

Vor dem Konformitätstest (KFT) bei Enghouse müssen alle Testfälle im eigenen Simulator grün sein (AUFGABE 3.3, 10.1 Punkt 5, Grundsatz 11). Diese Seite beschreibt, wie open-spri das prüft.

## Ablauf

Für jeden Testfall der KFT-Testfallliste (Blatt „Partner“ und Blatt „Supplier“) gibt es einen eigenen Test mit der TF-ID im Namen, z. B. `S_1_1_07_TAM_und_pos_TV [AG]`.

| Beteiligter | Rolle | Kommunikation |
|---|---|---|
| unsere Instanz | Blatt „Partner“: Auftraggeber (bei Z.6.1.07 ff. abgebender EKP); Blatt „Supplier“: Leistungserbringer | wird ausschließlich über die öffentliche REST-API gesteuert, wie ein Fremdsystem oder das Frontend (Grundsatz 4) |
| `SimulatedPartner` (Modul `simulator`) | die Gegenrolle, wie der KFT-Simulator | echtes SOAP mit eigenem Signaturschlüssel und eigenem Endpunkt |

Der `ScenarioRunner` geht die erwartete Meldungsfolge Schritt für Schritt durch. Wer einen Schritt ausführt, ergibt sich aus dem Meldungstyp: Den Absender nennt die Spezifikation, Aufträge und Änderungen kommen vom Auftraggeber. Geprüft wird:

- **Eingang beim Simulator:** Jede Nachricht von uns durchläuft beim Simulator dieselbe strenge Eingangsprüfung wie bei open-spri: Signatur, Version, XSD und Anlage 1 im Modus `enforce`. Nur bei Erfolg antwortet er mit TEQ OK.
- **Eingang bei uns:** Jede Nachricht des Simulators muss unser Zustandsautomat ohne Befund annehmen. Befunde sind etwa „Ablauf: …“ oder R-13 für einen unzulässigen Meldungscode.
- **Was wir senden:** Jede Meldung muss mit dem erwarteten Typ, Änderungskennzeichen und den erwarteten Meldungscodes beim Simulator ankommen.
- **Ende:** Am Schluss ist der Auftrag abgeschlossen.

Schlägt ein Testfall fehl, enthält die Meldung das Protokoll des Laufs:

```
S.1.1.13: ENTM: Befunde bei open-spri: […]
  open-spri → Auftrag NEU KFT1113-13-793
  Simulator → QEB (0000)
  open-spri → Storno
  Simulator → ERLM (0012) zu Storno
  FEHLER: …
```

## Ausführen

```bash
./gradlew :app:test --tests '*KftScenarioTest*'
# Bericht: backend/app/build/reports/tests/test/index.html (ein Eintrag je TF-ID)
```

Die Testfallliste (`Testfaelle_SPRIv4X_20250523.xlsx`) lädt der Build von ak-spri.de und prüft die Prüfsumme. Sie liegt nicht im Repository (OP-02).

**Meldungscodetabelle:** Liegt die AK-Tabelle lokal unter `docs/spec-original/SPRI_Meldungscodetabelle.csv`, laufen die Szenarien gegen sie und prüfen damit strenger. Sonst, etwa in der CI, verwenden sie eine Tabelle, die aus der Testfallliste abgeleitet ist (`TestCaseCodes`): alle Codes, die die Liste ausdrücklich nennt, mit den Meldungstypen, bei denen sie vorkommen. Diese Tabelle schränkt die Codes nicht auf Geschäftsfälle ein, weil die Liste oft nur `*2` angibt (etwa bei der ERLM einer Diagnose). Die Prüfung je Geschäftsfall (R-13) läuft deshalb nur mit der AK-Tabelle. Vor dem KFT müssen die Szenarien also lokal mit der AK-Tabelle grün sein.

## Selbsttest AG ↔ LE

Zwei open-spri-Instanzen spielen alle Testfälle gegeneinander (AUFGABE 10.1 Punkt 6). Beide werden nur über ihre REST-API gesteuert. Der Runner ist derselbe; statt des Simulators ist die Gegenseite die zweite Instanz (`InstanceCounterpart`).

- **Was die Gegenseite empfangen hat,** liest der Runner aus ihrem Auftragsverlauf.
- **Ihre Befunde** zählen als Abweisung, außer den erwarteten Befunden bei den Negativ-Testfällen.
- **Ihre Sendungen:** Sie schickt sie über ihren Postausgang; der Runner wartet dort auf unsere TEQ.
- **Fehlerhafte Aufträge der Negativ-Testfälle** sendet sie mit Übersteuerung (ADR-0008).

```bash
./gradlew :app:test -Pselftest    # nur der Selbsttest; läuft jede Nacht (.github/workflows/nightly.yml)
```

| Ergebnis | |
|---|---|
| 205 Testfälle | grün zwischen zwei Instanzen |
| S.8.2.10 | nur gegen den Simulator: eine echte Instanz erzwingt keine TEQ 0991 |
| Z.6.1.07 bis Z.6.1.10, Z.6.2.02 | nur gegen den Simulator: Die AKM-PV an uns als abgebenden EKP setzt beim Leistungserbringer den PV-Auftrag eines dritten Teilnehmers (aufnehmender EKP) voraus |

## Simulator als Container

Derselbe Simulator läuft auch als eigener Dienst `spri-simulator` (AUFGABE 10.3). Es ist das `api`-Image mit `OPENSPRI_MODE=SIMULATOR`, gedacht nur für Test und Entwicklung. So lässt sich eine echte Installation mit Datenbank und Proxy gegen alle Testfälle prüfen, etwa vor dem KFT-Termin.

```bash
# 1. Token der Instanz mit Rolle ADMIN und Scope orders:override (ADR-0008), in .env als OPENSPRI_SIM_TOKEN
curl -s -X POST https://spri.example.de/api/v1/tokens -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "KFT-Simulator", "role": "ADMIN", "scopes": ["orders:read", "orders:write", "le:messages:write", "partners:read", "partners:admin", "orders:override"]}'
# 2. Testfallliste nach ./config legen (scripts/fetch-spec.sh lädt sie nach docs/spec-original/)
cp docs/spec-original/Testfaelle_SPRIv4X_20250523.xlsx config/
# 3. Simulator starten
COMPOSE_PROFILES=proxy,simulator docker compose up -d spri-simulator
# 4. Testfälle ausführen: einzelne TF-IDs oder ohne Body alle
curl -s -X POST http://127.0.0.1:8090/sim/run -d '{"tfIds": ["S.1.1.01", "Z.13.1.01"]}'
curl -s -X POST http://127.0.0.1:8090/sim/run --max-time 900          # alle 211, dauert einige Minuten
# 5. Bericht
curl -s http://127.0.0.1:8090/sim/report.html -o kft-bericht.html
curl -s http://127.0.0.1:8090/sim/report.xml  -o kft-bericht.xml    # JUnit-XML
```

Beim Start holt sich der Simulator aus dem Onboarding-Blatt der Instanz deren Signaturzertifikat und S/PRI-Endpunkt. Er erzeugt einen eigenen Signaturschlüssel und trägt sich mit dem Carrier Code `KFTSIM` als Partner ein; bei einem Neustart ersetzt er dort sein Zertifikat. Er verwendet die aktive Meldungscodetabelle der Instanz und legt Projektvereinbarung, Vollmacht und Produktkatalog an wie die Szenario-Tests. `POST /sim/run` antwortet mit 200, wenn alle Testfälle bestanden sind, sonst mit 409 und den Gründen.

Ein kompletter Lauf stellt mehr REST-Anfragen, als die Ratenbegrenzung der Instanz erlaubt (Standard 1200 pro Minute und Token). Der Simulator wartet dann jeweils die Zeit aus `Retry-After` ab, der Lauf dauert also länger. Für einen schnellen Lauf auf der Testinstanz `OPENSPRI_RATE_LIMIT_REQUESTS_PER_MINUTE=100000` setzen.

**Achtung:** Der Simulator legt Testaufträge, Bestand und Stammdaten in der Instanz an. Er gehört nie an eine produktive Instanz.

## Auslegung der Testfallliste

| Notation | Umsetzung |
|---|---|
| `*2` („alle zulässigen MC“) | ein Code, den die Meldungscodetabelle für Meldungstyp und Geschäftsfall zulässt; bei einer ABBM der Code aus dem Hinweis, z. B. 1010 bei der Auftragsdublette |
| `ABM (0002, 0003 oder 0005->neuer Termin, *2)`, Titel „2x MC“ (S.1.1.02, S.8.1.02) | ABM mit den Codes 0002 und 0003 und einem späteren Termin |
| `->neuer Termin` | ABM mit einem Termin drei Tage nach dem Wunschtermin (Hinweis: mindestens einen Tag später) |
| `ABBM (2 zutreffende MC)` (S.1.2.6, S.8.2.6) | ABBM mit zwei Positionen: ein zulässiger Code und 1312 (Freitext) |
| `TEQ(991) … (wird vom LE wiederholt)` (S.8.2.10) | Der Simulator beantwortet unsere QEB mit TEQ 0991; unsere Outbox wiederholt sie selbst (Spezifikation 11.2.3.2). Bei S.1.2.10 (wir als AG) sendet der Simulator die QEB zweimal, laut Hinweis muss der AG die TEQ 0991 nicht erzeugen. |
| `<…>` | Austausch mit dem anderen Provider beim Providerwechsel. Als Auftraggeber (aufnehmender EKP) sehen wir ihn nicht, als Leistungserbringer führen wir ihn mit dem abgebenden EKP. |
| Vertragsnummer | vergibt der Leistungserbringer mit der ersten Meldung, die sie verlangt (ABM; ENTM nach einem Storno ohne ABM) |
| BA = X (Bestandsaufbau) | Vor dem Testfall läuft ein NEU-Auftrag mit der Standardkette `QEB (0000), ABM (*2), ERLM (0010), ENTM (0010)` zwischen denselben Partnern in denselben Rollen. Vertragsnummer und LineID aus dessen ABM sind der Bestand, auf den sich KUE-AG, AEN-LMAE, LAE, EST, Diagnose, KDLE und AKM-PV beziehen. |
| KUE-LE | beginnt ohne Auftrag mit der KDLE: als Leistungserbringer über `POST /terminations`, als Auftraggeber legt open-spri den Auftrag beim Eingang der KDLE an |
| abgebender EKP (Z.6.1.07 ff.) | Der Simulator schickt als Leistungserbringer die AKM-PV zum Bestandsvertrag, wir antworten über `POST /pv-requests/{id}/reply`; bei „neg. RUEM-PV“ mit Zustimmung `N` |
| Reklamation (Z.7.1.07, Z.14.1.07) | ein neuer Entstörauftrag mit derselben externen Auftragsnummer und `reklamation` (Störungsnummer des Leistungserbringers aus der QEB) |
| Projektkenner, Dritte | Vor den Szenarien trägt der Runner über die REST-API die Projektvereinbarung `PRJ-KFT-1` (R-12) und die Vollmacht des Bestellers `B4712/BL0816` für `AG4711/LN0815` (R-11) beim Simulator-Partner ein. Ohne sie würde open-spri eigene Aufträge mit Projektkenner abweisen und eingehende beanstanden. **Vor dem echten KFT muss der Betreiber beides für Enghouse genauso pflegen.** Meldungen zu solchen Aufträgen tragen `auftraggebernummerBesteller` und `vorabstimmungId` aus dem Auftrag. |
| Auftragskenner | `kenner/auftragskenner` mit eindeutiger `auftragsklammer` (Anlage 1 Zeile 166: nur einmal verwendbar) und `anzahlAuftraege` 1 |
| Vorabstimmung („Alle Anbieterwechsel relevanten Attribute“) | `vorabstimmungId` im Geschäftsfall (NEU, KUE-AG) |
| HomeID | `vormieter/homeId/homeIdNummer` (Anlage 1 Zeilen 113 bis 118) |
| Konnektivität | zusätzliche Position mit Produkt „Konnektivität“ (Anlage 1 Zeile 154); „mit Eigentümerdaten“: `standortVersand` mit dem Eigentümer und `montagehinweis` nach Variante 1 (Zeile 129). „Falsche Auftragsart“ (S.1.2.12, S.8.2.12) bezieht sich auf das Replikat; der Auftrag ist ein normaler Konnektivitätsauftrag, der Leistungserbringer antwortet mit ABBM 1510. |
| Providerwechsel als Leistungserbringer (Z.13.x) | Der Bestandsaufbau legt einen Vertrag des Simulators auf der Leitung an; er ist damit der abgebende EKP. Unsere AKM-PV, ABM-PV, ERLM-PV, ENTM-PV und ABBM-PV gehen an ihn, mit der Vertragsnummer des Bestandsvertrags (geprüft). Seine RUEM-PV kommt in seiner Rolle als abgebender EKP. Wie beim KFT spielt derselbe Partner Auftraggeber und abgebenden EKP. |
| Negativ-Testfälle als Leistungserbringer (Z.9.2.x, Z.11.2.x, Z.12.2.x, S.8.2.7) | Der Simulator sendet den fehlerhaften Auftrag. open-spri muss ihn mit dem passenden Befund erkennen, sonst schlägt der Testfall fehl: Termin mehr als 179 Tage voraus → R-05; Produkt „Dummy“ oder Leistungsmerkmal „TEST“ → „nicht im Produktkatalog“ (der Runner legt dafür unseren Produktkatalog an); Geschäftsfallart „Diagnose“ bei LAE → „Unzulässiger Geschäftsfall“; „Storno“ statt „Standard“ in einem neuen Auftrag → „unzulässiges Änderungskennzeichen“. Danach antworten wir wie in der Testfallliste, bei Storno statt Standard mit einer ABBM mit Änderungskennzeichen Storno. |
| Negativ-Testfälle als Auftraggeber (S.1.2.7, Z.2.2.x, Z.4.2.x, Z.5.2.x) | „Dummy“ und „TEST“ sind nach XSD und Anlage 1 gültig und gehen normal hinaus. Termin mehr als 179 Tage voraus, Geschäftsfallart „Diagnose“ und „Storno“ statt „Standard“ weist open-spri selbst ab; der Runner sendet sie mit `?override=` und einem Token mit `orders:override` (ADR-0008). |
| Diagnose (Z.15/Z.16) | GET mit den Parametern `BBdown`, `BBup`, SET mit `RESET`; die ERLM enthält je Parameter ein `dignoseergebnis` (SA-010) |

Jede Abweichung von dieser Auslegung beim echten KFT wird als SA-Eintrag nachgetragen.

## Coverage-Gate

Ein Testfall gilt erst als automatisiert, wenn alle seine Besonderheiten umgesetzt sind (`TestCaseVariants`): besondere Auftragsinhalte wie Projektkenner, Dritte, Vorabstimmung, HomeID oder Konnektivität, Meldungsvarianten wie neuer Termin, zwei Codes oder TEQ 0991, außerdem Bestandsaufbau (BA = X) und eine Auftragsvorlage für den Geschäftsfall. Sonst würde er mit einem Standardauftrag grün und bewiese nichts. Solche Testfälle erscheinen im Bericht als übersprungen, mit Grund.

Der Test `coverage gate` lässt den Build scheitern, wenn mehr Testfälle offen sind als erlaubt. Die erlaubte Zahl sinkt mit jedem Teil von M5 und ist am Ende 0.

## Stand

| Teil | Inhalt | offen |
|---|---|---|
| 1 | Simulator, Planer, Runner; NEU in beiden Rollen; Meldungsvarianten (neuer Termin, zwei Codes, Code aus dem Hinweis, TEQ 0991) | 155 von 211 |
| 2 | Auftragsvorlagen für alle Geschäftsfälle, Bestandsaufbau, KUE-LE, abgebender EKP, Reklamation, Diagnose | 60 von 211 |
| 3 | besondere Auftragsinhalte: Projektkenner, Auftragskenner, Dritte, Vorabstimmung, HomeID, Konnektivität | 20 von 211 |
| 4 | Bestand, Providerwechsel als Leistungserbringer | 14 von 211 |
| 5 | Negativ-Testfälle als Leistungserbringer; als Auftraggeber Produkt „Dummy“ und Leistungsmerkmal „TEST“ (S.1.2.7, Z.4.2.02, Z.5.2.02) | 4 von 211 |
| 6 | Negativ-Testfälle als Auftraggeber mit Übersteuerung (ADR-0008) | **0 von 211** |
| 7 | Simulator als Container (`POST /sim/run`, JUnit-XML und HTML) | – |
| 8 | Selbsttest AG ↔ LE zwischen zwei Instanzen, jede Nacht | – |

Alle 211 Testfälle beider Blätter laufen automatisiert. Das Coverage-Gate lässt keinen offenen Testfall mehr zu.
