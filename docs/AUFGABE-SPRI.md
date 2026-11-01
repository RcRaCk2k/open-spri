# Aufgabe: Open-Source S/PRI-Schnittstelle (bidirektional) mit REST-API

| | |
|---|---|
| **Dokumentstatus** | ENTWURF v0.2, wartet auf Entwicklungsfreigabe |
| **Projekt** | **open-spri**, `git@github.com:RcRaCk2k/open-spri.git` |
| **Umsetzung** | Claude Opus, alle Arbeitspakete (Entscheidung vom 2026-11-01) |
| **Stand** | 2026-11-01 |
| **Zielversion S/PRI** | V4.4 (Major 4, Minor 4), XSD 4.00.03 |
| **Lizenz der Software** | MIT, öffentlich auf GitHub |
| **Anhänge** | [Anhang A – Testfall-Matrix](ANHANG-A-Testfaelle.md). Wird lokal mit `scripts/gen-testmatrix.py` aus der Original-XLSX erzeugt und erst nach Klärung von OP-02 veröffentlicht. |
| **Quellen** | `docs/spec-original/` (Originale AK S&P), `docs/spec-text/` (Textauszüge) |

Bitte beachten: Normativ sind immer die Dokumente des AK S&P. Bei Widersprüchen gilt die Quelle, nicht dieses Dokument. Jeder gefundene Widerspruch wird in Kap. 15 und in `docs/spec-abweichungen.md` nachgetragen.

---

## 1. Ziel

Wir entwickeln eine Software, die **beide Rollen** der S/PRI-Schnittstelle übernimmt:

1. **Auftraggeber (AG / EKP, im KFT „Partner“):** Sie bestellt, ändert, kündigt und entstört Vorleistungsprodukte (Leitungen) bei anderen Netzbetreibern. Sie wertet deren Replikate und Online-Verfügbarkeitsabfragen aus.
2. **Leistungserbringer (LE / NGA, im KFT „Supplier“):** Sie nimmt Aufträge anderer Provider für unsere eigenen Leitungen an, bearbeitet sie und meldet den Fortschritt zurück. Sie stellt unsere Verfügbarkeiten als Replikat und als Online-Abfrage bereit.

Dazu kommen:
- eine **öffentlich dokumentierte REST-API** (OpenAPI 3.1). Über sie werden alle Funktionen gesteuert. Das mitgelieferte Web-Frontend benutzt **ausschließlich** diese API, es gibt keine privaten Endpunkte. Fremde CRM-, OSS- und Workflow-Systeme steuern die Software über dieselben Aufrufe und authentifizieren sich mit Long-Life-Tokens.
- eine **modulare Authentifizierung**: v1 bringt API-Tokens und lokale Benutzer mit. OAuth2/OIDC und SAML2 werden später als Module ergänzt, ohne dass der Kern umgebaut werden muss.
- eine **Auslieferung als Docker-Compose-Stack** mit getrennten Containern für API, MariaDB und Frontend.
- ein **erfolgreicher Konformitätstest (KFT) bei Enghouse** in beiden Rollen. Ziel ist ein **Softwarezertifikat** des AK S&P, das mit dem Release veröffentlicht wird (siehe Kap. 3).

## 2. Begriffe

| Begriff | Bedeutung |
|---|---|
| AG / EKP | Auftraggeber / Endkundenprovider. Bestellt beim LE. |
| LE / NGA / NGAB | Leistungserbringer / Netzbetreiber. Liefert das Vorleistungsprodukt. |
| EKPauf / EKPab | aufnehmender bzw. abgebender Endkundenprovider beim Providerwechsel (PV) |
| GF | Geschäftsfall (NEU, KUE-AG, KUE-LE, AEN-LMAE, LAE, PV, EST, DIAG-GET/SET) |
| Änderungskennzeichen | Standard, Storno, Terminverschiebung (TV), Reklamation (EST) |
| TEQ | technische Quittung. Synchrone SOAP-Response auf jeden Auftrag und jede Meldung. |
| MC | Meldungscode (Anlage 5, nur online im Confluence des AK) |
| KFT | Konformitätstest beim Simulator von Enghouse Networks |
| ICC | ITU Carrier Code. Identifiziert den Provider, ist Pflicht für den KFT. |
| Replikat | tägliche Verfügbarkeitsdatei des LE (XML, voll oder Delta, mit Metadatei) |
| WBCI | separate Schnittstelle zur Vorabstimmung beim Anbieterwechsel. Liefert die VorabstimmungsID. |
| Mandant | die Installation des Betreibers (eine Firma, ein ICC). v1 ist Single-Tenant (D-12). |

## 3. Zertifizierungsstrategie

### 3.1 Zertifikatsarten (laut ak-spri.de/spri/zertifikate)

| Zertifikat | Für wen | KFT nötig? | Kosten |
|---|---|---|---|
| **Schnittstellenzertifikat** | Wer selbst eine S/PRI-Schnittstelle betreibt | Ja | AK kostenlos, KFT-Gebühr bei Enghouse |
| **Softwarezertifikat** | Hersteller einer Software. „Der Hersteller darf die zertifizierte Software weiterverkaufen.“ | Ja, einmal | wie oben |
| Schnittstellenzertifikat **auf Basis eines Softwarezertifikats** | Wer eine zertifizierte Software selbst betreibt | **Nein.** Im Antrag wird das Unternehmen benannt, das die Software einsetzt. | AK kostenlos |
| **Nutzungszertifikat** | Wer **keine eigene** Schnittstelle betreibt, sondern einen Dienstleister mit Schnittstellenzertifikat nutzt | Nein | AK kostenlos |

**Korrektur gegenüber der Annahme im Gespräch:**
- Der KFT bei Enghouse ist **nicht kostenlos**. Enghouse erhebt eine Servicegebühr, deren Höhe nicht veröffentlicht ist. Kostenlos ist nur die Ausstellung des Zertifikats durch den AK.
- Für Nutzer, die unsere Software **selbst betreiben**, ist nicht das Nutzungszertifikat vorgesehen. Sie beantragen ein **Schnittstellenzertifikat für eine S/PRI-Software** (Antragsformular-Option). Dabei verweisen sie auf unser Softwarezertifikat, ein eigener KFT entfällt, und laut Website ist das ebenfalls kostenlos.
- Ein Nutzungszertifikat brauchen nur Firmen, die die Schnittstelle bei einem Dienstleister nutzen, etwa bei uns, falls wir Hosting anbieten.

Die Liste der erteilten Softwarezertifikate zeigt, dass das Modell etabliert ist. Beispiele sind dimari, OpenXS, Compax und Muth SEV, alle für „Supplier & Partner“.

### 3.2 Offene Frage an den AK S&P (vor dem KFT klären, OP-01)

Das Softwarezertifikat ist auf Weiterverkauf ausgelegt. Für eine **MIT-lizenzierte Open-Source-Software** sind diese Fragen offen:
1. Darf eine Open-Source-Software ein Softwarezertifikat erhalten? Wer gilt dann als „Hersteller“?
2. Welche Version und welcher Build sind abgedeckt, wenn Nutzer die Software forken oder verändern?
3. Gilt das Zertifikat für die Rolle „Partner & Supplier“ und für den hier festgelegten Umfang an Geschäftsfällen?

Daraus folgt eine technische Anforderung: **Das Release, das in den KFT geht, wird als signiertes und reproduzierbares Container-Image veröffentlicht** (Digest, SBOM). Die Software zeigt in der Oberfläche und über die API an, ob sie im **zertifizierten Build** läuft (Build-Digest-Vergleich, „certified build“-Kennzeichen).

### 3.3 Umfang des KFT

Wir lassen **beide Rollen** zertifizieren, gegen **S/PRI V4.4**. Der Umfang ergibt sich aus [Anhang A](ANHANG-A-Testfaelle.md):

| Rolle | PFLICHT | PFLICHT (Scope) | EMPFOHLEN | OUT (v2) |
|---|---|---|---|---|
| Auftraggeber (Blatt „Partner“) | 72 | 11 | 21 | 4 |
| Leistungserbringer (Blatt „Supplier“) | 69 | 11 | 19 | 4 |

Wichtig sind diese Regeln der Testfallliste:
- Optionale Geschäftsfälle wie EST werden **komplett Pflicht**, sobald wir sie anmelden.
- Optionale Testfälle mit Versionsbezug (Konnektivität ab 4.2) sind für die jeweilige Version verpflichtend.
- „Alle zulässigen MC müssen unterstützt werden“ (*2).
- Nach drei erfolglosen Versuchen bricht der KFT ab. Deshalb muss **unser eigener Simulator alle TF grün haben, bevor der Termin bei Enghouse gebucht wird** (Kap. 10).

## 4. Scope-Entscheidungen (Entscheidungslog)

| ID | Entscheidung | Status |
|---|---|---|
| D-01 | Beide Rollen: AG (inkl. abgebender EKP beim PV) und LE | entschieden |
| D-02 | Produktgruppen: FTTH, FTTB, FTTC. KOAX/HFC werden nicht aktiv angeboten, Datenmodell und Produktkatalog lassen sie aber zu. | entschieden |
| D-03 | Geschäftsfälle: NEU, KUE-AG, KUE-LE, AEN-LMAE, LAE, PV, **EST**. **DIAG-GET/SET erst in v2.** Das Datenmodell sieht Diagnose bereits vor. | entschieden (DIAG nicht genannt, daher v2) |
| D-04 | Konnektivitätsauftrag (FTTH-Ausbau, 2-stufig), HomeID und Bestellung im Namen Dritter sind im Scope | entschieden |
| D-05 | Verfügbarkeit: Replikat erzeugen (LE) und importieren (AG), Online-Abfrage anbieten (LE) und nutzen (AG) | entschieden |
| D-06 | WBCI/Vorabstimmung: wird im Setup konfiguriert, als „eigene WBCI-Schnittstelle“ oder „über Dienstleister“ (Kap. 5.8). v1 enthält keine eigene WBCI-Implementierung. | entschieden |
| D-07 | Das Frontend benutzt ausschließlich die öffentliche REST-API | entschieden |
| D-08 | ICC, Firmendaten und Rollen werden beim ersten Start im Setup-Assistenten der Web-Oberfläche abgefragt | entschieden |
| D-09 | Technik: Kotlin, Spring Boot 3, Apache CXF 4 (WSS4J), React + TypeScript, MariaDB | entschieden |
| D-10 | **Alle optionalen Testfälle außer DIAG umsetzen** (VZM, ZWM-LE, Projekt- und Auftragskenner, WITA-MC, neg. TEQ …). Begründung: Als öffentliche Software müssen wir die Wünsche aller künftigen Nutzer abdecken, und ein Nachzertifizieren ist teuer. | **Vorschlag, bitte bestätigen** |
| D-11 | Auftragsklammer (bis 20 Aufträge) und Kopplungskenner werden technisch übertragen und gespeichert. Die Koordination der Klammer auf LE-Seite (gemeinsamer Termin) bleibt ein manueller Workflow. | **Vorschlag, bitte bestätigen** |
| D-12 | v1 ist **Single-Tenant** (eine Installation = ein ICC). Mandantenfähigkeit für Dienstleister kommt in v2, die Datenbank bekommt aber schon jetzt eine `tenant_id`-Spalte. | **Vorschlag, bitte bestätigen** |
| D-13 | Die Datenquelle für eigene Verfügbarkeiten (LE) ist eine eigene Tabelle, gefüllt per REST-API oder CSV/XML-Import. Eine direkte Anbindung an GIS oder Netzinventar ist nicht Teil von v1. | **Vorschlag, bitte bestätigen** |
| D-14 | Für Ereignisse an Fremdsysteme gibt es **Webhooks** (HMAC-signiert) und zusätzlich einen Event-Feed per Polling (`GET /events?after=`) | **Vorschlag, bitte bestätigen** |

## 5. Fachliche Anforderungen

### 5.1 Rollen und Nachrichtenrichtungen

Die WSDL hat genau zwei Operationen: `annehmenAuftrag` und `annehmenMeldung`. Jede Seite ist zugleich SOAP-Server und SOAP-Client.

| Nachricht | AG sendet | AG empfängt | LE sendet | LE empfängt |
|---|---|---|---|---|
| Auftrag (alle GF und Änderungskennzeichen) | ✔ | | | ✔ |
| QEB, ABM, ERLM, ENTM, ABBM, VZM, TAM, MTAM, ZWM-LE, TBK-LE, KDLE | | ✔ | ✔ | |
| ZWM-AG, TBK-AG, ERLM-K | ✔ | | | ✔ |
| AKM-PV, ABM-PV, ERLM-PV, ENTM-PV, ABBM-PV (an den **abgebenden** EKP) | | ✔ (als EKPab) | ✔ | |
| RUEM-PV (vom abgebenden EKP) | ✔ (als EKPab) | | | ✔ |
| TEQ | auf jede empfangene Nachricht | auf jede gesendete Nachricht | ↔ | ↔ |

**Wichtig:** In der AG-Rolle sind wir auch **abgebender EKP**, wenn uns ein anderer Provider einen Kunden abwirbt (TF Z.6.1.07 bis Z.6.1.10 und Z.6.2.02). Wir empfangen dann eine AKM-PV, ohne selbst einen Auftrag gestellt zu haben, und beantworten sie mit RUEM-PV.

### 5.2 Geschäftsfälle und Meldungsverhalten

Normative Quellen sind Spezifikation Kap. 5, 6, 7.2 und 8 sowie Anlage 7 (EPKs). Die Software bildet für jeden GF einen **expliziten Zustandsautomaten** ab. Die gültigen Meldungsfolgen stehen in den Sequenzdiagrammen der Spezifikation und in der Spalte „Erwartete Meldungsfolge“ von Anhang A.

| GF | Änderungskennzeichen | zulässige Meldungen |
|---|---|---|
| NEU | Standard, Storno, TV | QEB, ABM (mehrfach), ERLM, ENTM, ABBM, VZM, TAM, MTAM, ZWM-LE, ERLM-K |
| KUE-AG | Standard, Storno, TV | QEB, ABM, ERLM, ENTM, ABBM, VZM |
| KUE-LE | Standard | KDLE, ERLM, ENTM |
| AEN-LMAE | Standard, Storno | QEB, ABM, ERLM, ENTM, ABBM, VZM |
| LAE | Standard, Storno, TV | QEB, ABM, ERLM, ENTM, ABBM, VZM, TAM, MTAM, ERLM-K |
| PV | Standard, Storno, TV | wie LAE, zusätzlich AKM-PV, RUEM-PV, ABM-PV, ERLM-PV, ENTM-PV, ABBM-PV |
| EST | Standard, Reklamation, Storno | QEB, ERLM, ENTM, ABBM, VZM, TAM, ZWM-LE, ZWM-AG, TBK-LE, TBK-AG |

**Diese Regeln müssen umgesetzt und getestet werden:**

- **R-01** Ein Webservice-Aufruf überträgt genau einen Auftrag oder eine Meldung. Sammelaufträge sind unzulässig.
- **R-02** Storno und TV werden mit **derselben externen Auftragsnummer** gesendet wie der Ursprungsauftrag. Sie sind inhaltlich identisch, nur das Änderungskennzeichen unterscheidet sich. Bei TV ist ein anderer Wunschtermin Pflicht. Ausnahme: Bei der automatischen Anschlussidentifikation darf der Termin gleich bleiben, dafür wird die ONT-Seriennummer oder Source-MAC mitgeschickt. Anlagen und Montageleistung dürfen sich bei TV ändern.
- **R-03** Pro Auftrag darf nur **ein offener Storno** bzw. **eine offene TV** bestehen, sonst folgt eine Abweisung.
- **R-04** Die ERLM eines Stornos (MC 0012) beendet den Ursprungsauftrag. Der Ursprungsauftrag bekommt danach keine eigene Abschlussmeldung mehr.
- **R-05** Der Ausführungstermin muss **unter 180 Tagen** in der Zukunft liegen. Ausnahme: TV im Konnektivitätsauftrag mit VorabstimmungsID.
- **R-06** Gültig ist jeweils die **zuletzt versandte** Meldung. Ein Beispiel ist die mehrfache ABM bei TV oder im TA-Fall (ABM mit MC 6012).
- **R-07** Konnektivitätsauftrag (NEU mit Unterposition „Konnektivität“) in zwei Schritten:
  1. Der LE stellt die Konnektivität her, der Wunschtermin wird dabei ignoriert. Statusmeldungen laufen als VZM (MC 15xx).
  2. Danach sendet der LE eine TAM (MC 6022). Der AG antwortet mit einer TV, die den realen Wunschtermin enthält. Erst dann folgen ABM, ERLM und ENTM.

  Eine TV ist erst nach ABM erlaubt. Eigentümerdaten, Kontaktdaten und Anlagen (Eigentümererklärung) sind vorgesehen.
- **R-08** TAM/MTAM: Eine TV ist nach einer TAM auch nach dem verbindlichen Liefertermin noch möglich. Bleibt die TAM unbeantwortet, folgen MTAM (6050) und danach ABBM. Beim LE ist der Timeout konfigurierbar.
- **R-09** ERLM-K (AG an LE) ist nur nach ABM und vor ERLM zulässig. Der GF schließt trotzdem mit ERLM und ENTM ab.
- **R-10** Wechselwirkungen: Gibt es für dieselbe technische Infrastruktur bzw. LineID bereits einen offenen Auftrag oder eine Kündigung, wird ein NEU abgewiesen (Spezifikation 5.x.x „Wechselwirkungen“). EST wird immer bearbeitet.
- **R-11** Bestellung im Namen Dritter: Der Block `besteller` enthält Auftraggeber- und Leistungsnummer des Bestellers. Als LE prüfen wir, ob eine **hinterlegte Vollmacht** vorliegt (Kap. 5.7). Alle Meldungen gehen ausschließlich an den Besteller.
- **R-12** Kenner: Projektkenner nur mit hinterlegter Projektvereinbarung, sonst Abweisung. Kopplungskenner nur zusammen mit Projektkenner. Auftragsklammer mit höchstens 20 Aufträgen. Kündigungen und Ersatz-Bereitstellungen dürfen nicht geklammert werden.
- **R-13** Die Meldungscodes werden aus einer versionierten, importierbaren Tabelle bezogen (Anlage 5). Eine Meldung darf mehrere MC enthalten, der Text ist höchstens 255 Zeichen lang. Der Freitext-MC 1312 wird unterstützt.
  - **Quelle:** Export aus dem Confluence des AK, `docs/spec-original/SPRI_Meldungscodetabelle.csv`. Format: UTF-16LE, Tab-getrennt, mehrzeilige Zellen. Spalten: `Page` (Excel-Formel `=HYPERLINK(url;"code")`), `Meldungstext`, `Meldungstyp (legacy)`, `Geschäftsfall (legacy)`, `Schnittstelle (legacy)`, `Gültig ab (legacy)`, `Prozessphase`, `Produktgruppe`. Listenwerte stehen zeilengetrennt in einer Zelle.
  - **Inhalt:** Stand Export 2026-11-01 sind es **214 Codes**, alle mit Schnittstelle S/PRI (teils zusätzlich WITA). Die Tabelle enthält auch Meldungstypen, die S/PRI V4.4 nicht kennt (`FAM`, `ERGM`, `ABBM-TR`, `VZM-PV`, WITA-Varianten). Der Importer übernimmt sie, markiert sie aber als „nicht S/PRI-V4.4-Meldungstyp“.
  - Der Importer (`POST /message-codes/import` und ein CLI-Befehl) liest genau dieses Format und zusätzlich ein neutrales UTF-8-CSV bzw. JSON. Er versioniert die Tabelle (Importdatum, Prüfsumme) und erzeugt daraus die Zulässigkeitsmatrix **MC × Meldungstyp × GF × Produktgruppe**. Ein Meldungscode, der für den Meldungstyp oder GF nicht zulässig ist, wird beim Senden abgewiesen (HTTP 422) und beim Empfang protokolliert, ohne die Annahme zu verweigern.
  - **Befund:** In den Testfällen werden die Codes **1089** (neg. Storno PV, Z.6.1.06/Z.13.1.06) und **7501** (DIAG, v2) verwendet. In der Tabelle fehlen sie. Sie werden als lokale Ergänzung mit Kennzeichnung gepflegt (OP-11).
- **R-14** Die HomeID wird übertragen, gespeichert und bei der Replikatsuche ausgewertet. Grundlage ist `Info_Doc_Bildungslogik_Home_ID_V.1.0`.
- **R-15** Die LineID wird nach `Struktur-und-Semantik-der-LineID_V.1.4` gebildet, als LE von uns. Format: Country.Carrier.LineCode, zum Beispiel `DEU.DTAG.1AC42O40`.

### 5.3 Eingangsprüfung (Spezifikation 10.4 und 11.2.3.1)

Beim **Empfang** (beide Rollen) erfolgt die Prüfung **synchron**, und die TEQ wird innerhalb der Zeitvorgaben aus Kap. 6 zurückgegeben:

| # | Prüfung | Ergebnis bei Fehler |
|---|---|---|
| 0 | Absender-IP steht auf der Whitelist des Partners | HTTP 403. Laut Security-Dokument 2.6 gibt es keine SOAP-Antwort. |
| 1 | WS-Security-Signatur gültig, Zertifikat gehört zum bekannten Partner (`signaturId` Issuer/Serial) | TEQ mit Fehlercode, nur Fehlernummer. Der Code ist in der Tabelle nicht festgelegt, siehe OP-11. |
| 2 | Major-Release passt zum Endpunkt | TEQ-Fehler |
| 3 | XSD-Validierung | TEQ-Fehler |
| 4 | Attribute und Wertebereiche, Muss/Kann je Kombination aus Produktgruppe, GF und Änderungskennzeichen (Anlage 1) | TEQ-Fehler |
| 5 | Dublette, d. h. derselbe Auftrag wird erneut eingestellt | ABBM (MC 1010) |
| 6 | Produkt im Katalog, Rahmenvertrag bzw. Leistungs-Nr. vorhanden | ABBM |

**TEQ-Codes laut Meldungscodetabelle:** `OK`, `0990` (Funktion aktuell nicht unterstützt), `0991` (Schnittstelle überlastet), `0993` (Versionsnummer fehlerhaft), `0995` (Datenstruktur unzulässig), `0999` (technischer Fehler). Vorläufige Zuordnung: Version → 0993; XSD und Anlage-1-Regeln → 0995; nicht aktivierter GF (z. B. DIAG in v1) → 0990; Überlast bzw. Rate Limit → 0991; Signatur und sonstige Fehler → 0999 (OP-11).

Erst nach erfolgreicher Prüfung wird **dauerhaft gespeichert** (Roh-XML inkl. Signatur) und eine positive TEQ zurückgegeben. Die fachliche Verarbeitung läuft danach asynchron.

**Prüfregeln aus Anlage 1:** Die Excel-Datei wird **zur Build-Zeit** in eine maschinenlesbare Regeldatei umgewandelt (YAML/JSON, versioniert im Repo, Generator-Skript enthalten). Daraus entsteht der Validator. Hart codierte Regeln sind nicht erlaubt.

### 5.4 Verfügbarkeit: Replikat und Online-Abfrage

**Als LE (Export):**
- Täglicher Job erzeugt ein Vollreplikat und optional ein Deltareplikat nach `Replikat.xsd` v1.7. Format ist XML (ZIP), dazu eine Metadatei `<CarrierId>_<YYYYMMDD>_meta.xml` mit Schemaversion, Dateiname, Format, MD5, Zeitpunkt und CarrierCode.
- Das Replikat liegt **bis 06:00 Uhr** bereit. Die letzten **31 Tage** werden lückenlos vorgehalten.
- Download über HTTPS-Endpunkt der API (`/replikat/...`). Zugriff je Partner per Token oder Basic-Auth und IP-Whitelist. Die Felder je Partner sind konfigurierbar (bilaterale Vereinbarung über optionale Felder), Kontingentzuordnung ist möglich.
- Das Replikat enthält keine personenbezogenen Daten.

**Als AG (Import):**
- Je Partner wird eine Quelle konfiguriert (URL, Zugangsdaten, Zeitplan). Der Import läuft täglich mit MD5-Prüfung und schemavalidiert. Deltas werden auf das letzte Vollreplikat angewendet. Fehler werden protokolliert und gemeldet.
- Adresssuche über REST (PLZ, Ort, Straße, Hausnummer + Zusatz, Geokoordinaten, HomeID) über alle Partner hinweg. Ergebnis: verfügbare Produkte, Ausbaustatus, Bereitstellungszeit, ob Eigentümerdaten nötig sind usw.
- Ein Treffer lässt sich direkt in einen vorbefüllten NEU-Auftrag übernehmen (Replikat-Spezifikation Kap. 4).

**Online-Abfrage:** Der AK hat dafür **keinen Standard** definiert (OP-05). Deshalb gilt:
- **Als LE** bieten wir einen dokumentierten REST-Endpunkt `GET /api/v1/availability?...` an. Das Antwortschema entspricht 1:1 den Feldern eines Replikat-Datensatzes und ist damit fachlich standardnah. Zugriff per Partner-Token.
- **Als AG** gibt es eine Adapter-Schnittstelle (`AvailabilityProvider`-SPI) je Partner. v1 liefert zwei Implementierungen mit: „Replikat (lokal)“ und „Online-Abfrage gegen eine Instanz dieser Software“. Weitere Adapter für Partner-APIs können als Plugin ergänzt werden.

### 5.5 LE-Workflow: Bearbeitung eingehender Aufträge

Die Software ist **kein Netz-Provisionierungssystem**. Als LE ist sie ein **Auftragsmanagement mit Arbeitsvorrat**:
- Nach Eingang eines Auftrags stellt sie automatisch fachliche Vorprüfungen an (Produkt, Rahmenvertrag, Vollmacht, Adresse oder LineID bekannt, Wechselwirkungen). Das Ergebnis wird per Regel **automatisch** gemeldet (QEB bzw. ABBM) oder als **Aufgabe** in den Arbeitsvorrat gelegt. Ob das automatisch geschieht, lässt sich je Prüfung konfigurieren.
- Jede ausgehende LE-Meldung lässt sich **per REST-API** (Fremdsystem) oder **im Frontend** (Sachbearbeiter) auslösen, zum Beispiel `POST /api/v1/orders/{id}/messages {type: ABM, termin…, codes:[…]}`.
- Die Software prüft gegen den Zustandsautomaten, ob die Meldung im aktuellen Zustand zulässig ist. Im Fehlerfall antwortet sie mit HTTP 409 und nennt die erlaubten Folgemeldungen.
- **KUE-LE** (KDLE) kann von uns ohne vorherigen Auftrag ausgelöst werden.

### 5.6 AG-Workflow

- Aufträge aller GF werden per REST oder Frontend erstellt (Entwurf → Senden). Storno und TV sind Aktionen auf einem bestehenden Auftrag.
- Eingehende Meldungen ändern den Auftragsstatus und erzeugen Ereignisse (Webhook/Event-Feed). Darauf reagieren zu müssen ist vor allem bei TAM, MTAM und AKM-PV wichtig, weil dort Fristen laufen.
- AG-Meldungen (ZWM-AG, TBK-AG, ERLM-K, RUEM-PV) werden als Aktionen angeboten.
- Bestände (Verträge, LineIDs) werden aus ERLM/ENTM aufgebaut. Sie sind die Grundlage für KUE-AG, AEN-LMAE, LAE und EST.

### 5.7 Bestellung im Namen Dritter

- **Als AG (Besteller):** Ein Auftrag kann für einen anderen Auftraggeber (Auftraggeber- und Leistungsnummer) gestellt werden. Die Stammdaten der Dritten werden im Partner-Kontext gepflegt.
- **Als LE:** Vollmachten werden verwaltet (Besteller → Auftraggeber, gültig von/bis, Dokument als Anlage). Ohne gültige Vollmacht folgt eine ABBM.

### 5.8 Providerwechsel und WBCI-Modus (Setup-Option)

Im Setup wird `wbci.mode` festgelegt:

| Modus | Verhalten |
|---|---|
| `SERVICE_PROVIDER` (v1, unser Fall) | Die VorabstimmungsID kommt vom WBCI-Dienstleister. Sie wird beim PV- oder NEU-Auftrag **manuell bzw. per REST eingegeben** und formal geprüft (AN 16–21). Optional gibt es einen Import-Webhook für den Dienstleister. |
| `OWN_INTERFACE` | Die VorabstimmungsID kommt aus einem eigenen WBCI-System über eine Adapter-SPI (`PreAgreementProvider`). v1 liefert nur die SPI und eine „manuell“-Implementierung. Eine WBCI-Implementierung ist nicht Teil dieser Aufgabe. |
| `NONE` | PV ist deaktiviert. Hinweis: Für die Zertifizierung ist PV Pflicht, das gilt nur für Nutzer ohne PV-Bedarf. |

Die Workflows unterscheiden sich nur in der Herkunft der ID und in der Zuständigkeit. Die WBCI-Vorabstimmung „ohne WBCI“ (Referenz [6]) wird als Variante von `SERVICE_PROVIDER` behandelt.

### 5.9 Partnerverwaltung

Je Partner (Gegenstelle) wird hinterlegt:
- Name, ICC/CarrierCode, Rollen (wir als AG ihm gegenüber und/oder als LE)
- SOAP-Endpunkt der Gegenstelle mit Major-Release, außerdem Auftraggebernummern und Leistungsnummern (Rahmenverträge)
- **Signaturzertifikat** der Gegenstelle für die Prüfung, **IP-Whitelist** (eingehend), E-Mail-Rückfallkanal und technischer Kontakt
- Produktkatalog bzw. Produktbezeichner je Partner (Anlage 4 ist nicht normativ, deshalb pflegbar), Produktwechselmatrix (Anlage 6)
- Retry-Parameter (Intervall, maximale Anzahl), Replikat-Quelle bzw. -Freigabe, Online-Abfrage-Adapter
- Projektvereinbarungen (Projektkenner) und Vollmachten

### 5.10 Setup-Assistent (erster Start)

Solange `setup.completed = false` gilt, leiten Frontend und API (alles außer `/setup` und `/health`) auf den Assistenten um. Der Zugang ist durch ein **einmaliges Setup-Token** geschützt. Es wird beim ersten Start ins Container-Log geschrieben, damit niemand die offene Instanz übernehmen kann.

Schritte:
1. **Admin-Benutzer** anlegen
2. **Firmendaten**: Name, Anschrift, **ICC/CarrierCode**, Kontakt-E-Mail für Ausfall- und Wartungsmeldungen
3. **Rollen**: AG, LE oder beide
4. **Optionen**: aktivierte GF (EST; DIAG ab v2), Konnektivität, Bestellung Dritter, `wbci.mode`
5. **Zertifikate**: Signaturschlüssel erzeugen (RSA 3072, selbstsigniert erlaubt) oder hochladen (PKCS#12). Dazu ein Hinweis zum TLS-Zertifikat (Kap. 7.4).
6. **Öffentliche URLs**: SOAP-Endpunkt, Replikat-Download, Online-Abfrage. Der Assistent zeigt die angepasste **WSDL zum Download für Partner** an (Service-Endpoint ersetzt).
7. Zusammenfassung → `setup.completed = true`. Alle Einstellungen lassen sich später in den Einstellungen ändern (mit Audit).

## 6. Nichtfunktionale Anforderungen

| ID | Anforderung | Quelle |
|---|---|---|
| NF-01 | TEQ für 95 % der Anfragen ohne Anlagen in **≤ 10 s**, maximal 30 s, mit Anlagen bis 120 s. **Internes Ziel: p95 < 1 s.** | Spez. 11.4 |
| NF-02 | Verfügbarkeit Mo–Fr 6–24 Uhr für NEU/KUE/AEN/PV, **7×24 für EST**, 99,5 % im Jahresmittel. Dafür gibt es eine Betriebsdokumentation zu Hochverfügbarkeit, Backup und Restore. | 11.3 |
| NF-03 | **Meldungsreihenfolge garantiert** je Auftrag und je Sachverhalt (LineID/Vertragsnummer) über Auftragskontexte hinweg | 11.2.3.3 |
| NF-04 | Ausgehende Retries mit konfigurierbarem Intervall und maximaler Anzahl. Danach Status „nicht zustellbar“, Fehlerliste und manueller Neuversand. Bei Ausfall der Gegenstelle **höchstens eine E-Mail pro Tag und Ausfall**, die Tagesfrist beginnt nach erfolgreichem Request neu. | 11.2.3.2 |
| NF-05 | Kommt keine TEQ innerhalb von 10 s auf einen eigenen **Auftrag**, gilt: kein automatisches Neusenden mit derselben Nummer. Der Status wird „unklar“, und der Nutzer entscheidet über ein Neusenden mit **neuer** Auftragsnummer. Die spätere Dubletten-ABBM wird verarbeitet. | 11.2.3.1 |
| NF-06 | Liefert die Gegenstelle auf unsere Meldung eine TEQ ≠ OK, gilt die Meldung trotzdem als zugestellt (als LE) und landet in der Fehlerliste | 11.2.3.2 |
| NF-07 | Zeichensatz UTF-8, XML 1.0, SOAP 1.1, WSDL 1.1, XSD 1.0 | 10, 10.1 |
| NF-08 | Wartungsfenster: Partner werden 5 Werktage vorher per E-Mail informiert. Die Funktion „Wartung ankündigen“ verschickt eine Mail an alle Partner. | 11.5 |
| NF-09 | **Revisionssicheres Nachrichtenarchiv**: jede ein- und ausgehende SOAP-Nachricht roh (inkl. Signatur) mit Zeitstempel, Richtung, Partner und TEQ-Ergebnis | Nachweis im KFT und im Betrieb |
| NF-10 | Datenschutz: Endkundendaten (Name, Anschrift, Kontakt, Eigentümer) sind personenbezogen. Aufbewahrung ist konfigurierbar, mit Pseudonymisierung bzw. Löschung abgeschlossener Aufträge nach X Monaten. Zugriff wird protokolliert. | DSGVO |
| NF-11 | Observability: strukturierte Logs (JSON), Prometheus-Metriken (TEQ-Latenz, Queue-Tiefe, Fehlerraten je Partner), `/health` (liveness/readiness) | Betrieb |
| NF-12 | Internationalisierung: Oberfläche zuerst auf Deutsch (Fachsprache), i18n-fähig. API-Feldnamen auf Englisch, S/PRI-Fachbegriffe wie im Standard. | – |
| NF-13 | Reproduzierbare, signierte Builds (Cosign), SBOM (CycloneDX), Abhängigkeits-Scan in CI | Kap. 3.2 |

## 7. Sicherheit

### 7.1 Transport (S/PRI ↔ Partner), Security-Dokument V3.0 Kap. 2.4

- HTTPS mit **mindestens TLS 1.2**, TLS 1.3 bevorzugt, Port 443
- X.509 v3, SHA256withRSA mit **RSA ≥ 3072 Bit** oder **ECC ≥ 256 Bit**
- Zertifikat einer **CA von der AK-Whitelist** (Let's Encrypt ist zugelassen), **keine selbstsignierten Zertifikate**
- Kein Pinning, Vertrauen in die Root-CAs. Ausgehend prüft der Client den Partner gegen den System-Truststore. Ein optional einschränkbarer Truststore enthält nur die Whitelist-CAs.

### 7.2 Nachrichtensignatur (WS-Security), Kap. 2.5 und `policy.xml`

- SOAP Message Security 1.1: Body und Anhänge werden signiert. AsymmetricBinding mit X509 v3 Token (`WssX509V3Token10`), AlgorithmSuite Basic256, Layout Strict, `OnlySignEntireHeadersAndBody`
- Signaturzertifikat X.509 v3, SHA256withRSA, **RSA ≥ 3072 Bit**, selbstsigniert erlaubt, Laufzeit unbeschränkt
- **Zwei getrennte Schlüssel** für TLS und Signatur (Empfehlung des AK)
- Zertifikatswechsel mit Überlappung: Je Partner sind mehrere gültige Prüfzertifikate erlaubt. Der eigene Schlüssel lässt sich mit Vorankündigung tauschen.
- Private Schlüssel werden verschlüsselt gespeichert (Master-Key aus Docker-Secret bzw. Umgebungsvariable, nie in der Datenbank im Klartext)

### 7.3 Angriffsfläche

- Eingehende SOAP-Aufrufe nur von den **freigeschalteten IPs** des jeweiligen Partners (Kap. 2.6). Durchgesetzt wird das in der App über eine vertrauenswürdige Proxy-Kette (X-Forwarded-For nur von konfigurierten Proxies) und optional zusätzlich im Proxy.
- XML-Härtung: XXE deaktiviert, Größenlimits, Begrenzung der Entity Expansion
- REST: Rate Limiting je Token, CORS nur für die eigene Frontend-Origin, Security-Header, CSRF-Schutz für Cookie-Sessions

### 7.4 TLS-Bereitstellung im Stack

Der SOAP-Endpunkt muss aus dem Internet erreichbar sein und ein Whitelist-CA-Zertifikat haben. Vorschlag: ein **optionaler vierter Container `proxy` (Caddy oder Traefik) mit ACME/Let's Encrypt** als Profil im Compose-Stack. Alternativ kann ein vorhandener Reverse-Proxy des Betreibers genutzt werden, dafür liefert die Dokumentation Beispiele für nginx und Traefik. TLS darf am Proxy enden, weil WS-Security auf Nachrichtenebene unabhängig davon ist.

### 7.5 Authentifizierung und Autorisierung der REST-API (modular)

**Architektur:** eine SPI `AuthenticationModule` im Modul `auth-api`. Jedes Modul liefert:
- `id`, `displayName` und die Konfiguration (JSON-Schema, damit das Frontend das Formular selbst erzeugen kann)
- einen Spring-Security-`AuthenticationProvider` bzw. eine Filter-Konfiguration
- optional einen Login-Flow für das Frontend (z. B. Redirect bei OIDC/SAML)

Module werden per Classpath (ServiceLoader/Spring-Auto-Configuration) gefunden und in der Konfiguration aktiviert.

**v1-Module:**
1. `local-users`: Benutzername und Passwort (Argon2id), optional TOTP. Das Frontend erhält eine Session (HttpOnly-Cookie, SameSite=Strict) oder ein kurzlebiges Token.
2. `api-token` (**Long-Life-Token**):
   - Format `spri_<prefix>_<secret>`, gespeichert wird nur ein Hash (SHA-256 oder Argon2), angezeigt wird es nur einmal
   - Ablaufdatum optional, sofortiges Widerrufen möglich, `last_used_at` und Quell-IP werden protokolliert
   - Header `Authorization: Bearer …`
   - Optional mit IP-Bindung
   - Tokens gehören einem **Service-Account** oder Benutzer und erben dessen Rollen bzw. Scopes

**Später als Module (nicht Teil von v1, aber per Architekturtest nachgewiesen, dass sie andocken können):** `oidc` (OAuth2 Resource Server und Login), `saml2` (Spring Security SAML2), `ldap`.

**Autorisierung (RBAC):** Rollen `ADMIN`, `OPERATOR_AG`, `OPERATOR_LE`, `VIEWER`, `INTEGRATION`, außerdem feingranulare Scopes wie `orders:write`, `orders:read`, `le:messages:write`, `availability:read`, `partners:admin`, `settings:admin`. Die Prüfung erfolgt im Service-Layer, nicht nur am Controller.

**Partner-Zugriffe** (Replikat-Download, Online-Abfrage) laufen über eigene Partner-Tokens mit Scope `partner:<id>:availability`.

## 8. Architektur

### 8.1 Container (docker-compose)

```
                 Internet (Partner)                Intern (Nutzer, CRM)
                        │ HTTPS 443                        │
                 ┌──────▼───────┐  (optional, Profil "proxy")
                 │ proxy (Caddy)│──────────────┐
                 └──────┬───────┘              │
              /spri/v4  │ /replikat /api       │ /
                 ┌──────▼──────────┐   ┌───────▼────────┐
                 │ api (Kotlin)    │◀──│ frontend       │  nginx + React-SPA,
                 │ SOAP+REST+Jobs  │   │ (nginx)        │  proxy /api → api
                 └──────┬──────────┘   └────────────────┘
                        │ JDBC
                 ┌──────▼──────────┐   ┌────────────────────────┐
                 │ db (MariaDB 11) │   │ spri-simulator         │ nur Profil
                 └─────────────────┘   │ (gleiches Image, Modus)│ "dev"/"test"
                                       └────────────────────────┘
```

- `api`: ein Image mit Spring Boot. Es enthält den SOAP-Endpunkt (CXF), die REST-API, den Scheduler (Outbox-Dispatcher, Replikat-Jobs, Retries) und Flyway-Migrationen beim Start.
- `frontend`: statischer Build hinter nginx. Leitet `/api` an `api` weiter, damit es keine CORS-Probleme gibt.
- `db`: MariaDB 11 LTS mit Volume, Healthcheck, Backup-Skript (mariadb-dump) als Dokumentation bzw. Sidecar-Beispiel
- `spri-simulator`: dasselbe `api`-Image im Modus `SIMULATOR`, siehe Kap. 10.3
- Konfiguration über Umgebungsvariablen bzw. `.env`, Geheimnisse über Docker-Secrets. Images für amd64 und arm64.

### 8.2 Modulstruktur (Gradle Multi-Module, Kotlin)

| Modul | Inhalt | Abhängig von |
|---|---|---|
| `domain` | Fachmodell und Zustandsautomaten je GF, Regeln R-01 bis R-15, Ports (Interfaces). **Kein** SOAP, Spring oder JPA. | – |
| `spri-schema` | XSD/WSDL (Anlage 3), JAXB-Codegen (cxf-codegen/xjc), Anlage-1-Regeldatei und Generator | – |
| `spri-soap` | CXF-Endpunkt und -Client, WSS4J-Signatur, Mapping JAXB ↔ Domain, TEQ-Erzeugung, Eingangsprüfung (Kap. 5.3) | domain, spri-schema |
| `replikat` | Replikat-Export, -Import, Delta-Logik, Online-Abfrage, `AvailabilityProvider`-SPI | domain |
| `persistence` | Spring Data JPA oder jOOQ (Entscheidung beim Entwickler, Empfehlung jOOQ), Flyway, Repositories | domain |
| `outbox` | transaktionaler Postausgang, Dispatcher mit Reihenfolgegarantie, Retry, E-Mail-Rückfall | domain, persistence |
| `auth-api`, `auth-local`, `auth-token` | SPI und v1-Module (Kap. 7.5) | – |
| `rest-api` | Controller, DTOs, OpenAPI (springdoc), Problem Details (RFC 9457), Webhooks, Event-Feed | domain, auth-api |
| `setup` | Setup-Assistent und Einstellungen | rest-api |
| `simulator` | KFT-ähnlicher Simulator (Rolle AG oder LE, skriptbar über TF-ID) | spri-soap |
| `app` | Spring-Boot-Assembly, Profile `default`, `simulator` | alle |

**Architekturregel (mit ArchUnit erzwungen):** `domain` hängt von keinem Framework ab. Protokolladapter (`spri-soap`, später `fit-rest`) hängen nur von `domain` ab. **Begründung:** Der AK arbeitet mit **FIT** an einem Nachfolger, der S/PRI, WITA und WBCI vereinen soll. Ein künftiger FIT-Adapter soll dieselbe Fachlogik nutzen können.

### 8.3 Verarbeitung (Sequenz)

**Eingang** (Partner → wir):
1. HTTPS → IP-Prüfung → CXF + WSS4J (Signatur) → Schema → Version → Anlage-1-Regeln
2. **Eine** DB-Transaktion: Nachricht archivieren, Inbox-Eintrag anlegen, Dubletten prüfen (Unique-Key Partner + ext. Auftragsnr + Änderungskennzeichen + Hash)
3. TEQ OK zurück
4. Inbox-Worker verarbeitet die Nachricht fachlich (Zustandsautomat, Ereignisse, Automatikregeln)

**Ausgang** (wir → Partner):
1. Fachaktion (REST) → Zustandsautomat prüft → **gleiche Transaktion**: Zustand ändern und Outbox-Eintrag mit Sequenznummer je Reihenfolgeschlüssel anlegen
2. Der Dispatcher sendet je Reihenfolgeschlüssel streng **FIFO**: Eine Nachricht geht erst raus, wenn die vorige eine TEQ erhalten hat oder endgültig gescheitert ist
3. Der Reihenfolgeschlüssel ist `partner + lineId`, falls vorhanden, sonst `partner + externe Auftragsnummer`. Parallelität ist über verschiedene Schlüssel hinweg erlaubt.
4. Für mehrere API-Instanzen wird der Dispatcher-Lock über die DB vergeben (`SELECT … FOR UPDATE SKIP LOCKED`, das kann MariaDB ab 10.6)

### 8.4 Datenmodell (grob, Details beim Entwickler)

- `tenant` (Vorbereitung D-12), `company_settings`, `setup_state`
- `partner`, `partner_endpoint`, `partner_certificate`, `partner_ip_allowlist`, `framework_contract` (Auftraggeber-Nr., Leistungs-Nr.), `power_of_attorney`, `project_agreement`
- `product_catalog`, `product_feature`, `product_change_matrix`
- `order` (Rolle AG/LE, Partner, GF, ext. Auftragsnr., Status, Zustandsdaten, LineID, Vertragsnr., HomeID, VorabstimmungsID, Termine, Kenner) + `order_position` + `order_attribute` (generische Attribute aus Anlage 1, Key-Value mit Typ) + `order_contact` + `order_attachment`
- `order_change_request` (Storno, TV, Reklamation) mit Status
- `message` (Typ, Richtung, MC-Liste, Termine, Bezug auf Order) + `message_code` (Referenztabelle aus Anlage 5, versioniert)
- `soap_archive` (Rohnachricht, Hash, Signaturinfo, TEQ-Ergebnis, Dauer)
- `outbox`, `inbox`, `delivery_attempt`, `undeliverable_report`
- `inventory_contract` (Bestand aus ERLM/ENTM, je Rolle)
- `availability_site` (eigene Verfügbarkeiten, LE), `replikat_snapshot`, `replikat_import`, `partner_availability` (importierte Daten, AG)
- `user`, `role`, `service_account`, `api_token`, `auth_module_config`, `audit_log`
- `webhook_subscription`, `event` (Event-Feed, monoton steigende ID)

### 8.5 REST-API (Überblick, wird als OpenAPI 3.1 ausgearbeitet)

Pfad `/api/v1`. JSON, Paginierung per Cursor, `Idempotency-Key`-Header für alle POSTs, die eine S/PRI-Nachricht auslösen, Fehler als RFC 9457, ETags bzw. optimistisches Locking.

| Bereich | Endpunkte (Auszug) |
|---|---|
| Setup | `GET/POST /setup/*` (nur ohne abgeschlossenes Setup oder mit Setup-Token) |
| Auth | `POST /auth/login`, `POST /auth/logout`, `GET /auth/modules`, `GET/POST/DELETE /tokens` |
| Partner | `CRUD /partners`, `/partners/{id}/certificates`, `/ip-allowlist`, `/contracts`, `/powers-of-attorney`, `/projects`, `GET /partners/{id}/wsdl` |
| Produkte | `CRUD /products`, `/product-change-matrix` |
| Aufträge (AG) | `POST /orders` (Entwurf), `POST /orders/{id}/send`, `POST /orders/{id}/cancel` (Storno), `POST /orders/{id}/reschedule` (TV), `POST /orders/{id}/complaint` (EST-Reklamation), `POST /orders/{id}/messages` (ZWM-AG, TBK-AG, ERLM-K) |
| Aufträge (LE) | `GET /orders?role=LE&status=…`, `POST /orders/{id}/messages` (QEB, ABM, VZM, TAM, MTAM, ZWM-LE, TBK-LE, ERLM, ENTM, ABBM), `POST /terminations` (KUE-LE/KDLE) |
| Providerwechsel | `GET /pv-requests` (AKM-PV als EKPab), `POST /pv-requests/{id}/reply` (RUEM-PV) |
| Allgemein | `GET /orders/{id}` (inkl. Timeline und erlaubter Aktionen), `GET /orders/{id}/allowed-actions`, `GET /messages/{id}/raw` (Archiv, Scope nötig) |
| Bestand | `GET /inventory`, `GET /inventory/{lineId}` |
| Verfügbarkeit | `GET /availability/search` (AG, über Partner), `CRUD /availability/sites` + `POST /availability/sites/import` (LE), `GET /availability` (Online-Abfrage für Partner) |
| Replikat | `GET /replikat/{file}` (Partner-Download), `GET/POST /replikat/sources` (AG-Import), `POST /replikat/sources/{id}/run` |
| Meldungscodes | `GET /message-codes`, `POST /message-codes/import` |
| Ereignisse | `GET /events?after=`, `CRUD /webhooks` (HMAC-SHA256-signiert, Retry) |
| Betrieb | `GET /health`, `/metrics`, `GET /deliveries?status=failed`, `POST /deliveries/{id}/retry`, `POST /maintenance-announcements` |
| Einstellungen | `GET/PUT /settings/*`, `GET /about` (Version, Build-Digest, zertifizierter Build ja/nein) |

**Jede** Funktion des Frontends muss über diese API erreichbar sein. Das wird durch E2E-Tests sichergestellt, die nur gegen die API laufen.

### 8.6 Frontend (React + TypeScript)

- Vite, React 18+, TypeScript strict, TanStack Query, React Router, ein Komponenten-Framework (Vorschlag: Mantine oder MUI, Entscheidung beim Entwickler), `react-hook-form` + `zod`
- Der API-Client wird aus der OpenAPI-Datei generiert (`openapi-typescript`/`orval`). Es gibt keine handgeschriebenen Endpunkt-URLs.
- Seiten:
  - Setup-Assistent, Login, Dashboard (offene Aufgaben, Fristen TAM/MTAM/AKM-PV, Zustellfehler)
  - Auftragsliste und -detail mit Timeline aller Meldungen, Aktionen nur gemäß `allowed-actions`, Roh-XML-Ansicht
  - Auftrag anlegen (Assistent je GF, vorbefüllt aus Verfügbarkeitssuche oder Bestand)
  - LE-Arbeitsvorrat, Verfügbarkeitssuche, Bestand
  - Partner, Produkte, Meldungscodes, Benutzer/Tokens, Webhooks, Einstellungen, Zustellmonitor, Audit-Log
- Barrierearm (WCAG 2.1 AA als Ziel), deutsch

## 9. Technik-Rahmen (festgelegt)

| Bereich | Vorgabe |
|---|---|
| Sprache, Laufzeit | Kotlin 2.x auf JDK 21 (LTS) |
| Framework | Spring Boot 3.x, Spring Security 6 |
| SOAP | Apache CXF 4.x (JAX-WS), WSS4J, JAXB-Codegen aus Anlage-3-XSD/WSDL |
| DB | MariaDB 11 LTS, Flyway |
| Build | Gradle (Kotlin DSL), Version Catalog |
| Tests | JUnit 5, Kotest (optional), Testcontainers (MariaDB), WireMock, ArchUnit, Playwright (E2E Frontend), Gatling oder k6 (Last) |
| Frontend | React + TypeScript, Vite, pnpm, Vitest |
| CI | GitHub Actions: Build, Tests, Simulator-Suite, Images (multi-arch), Cosign, SBOM, Trivy/Dependabot |
| Lizenz | MIT, `LICENSE`, `NOTICE` (Hinweis auf AK-Dokumente, Kap. 14) |

## 10. Teststrategie und Qualitätssicherung

### 10.1 Testebenen

1. **Unit-Tests (domain):** jeder Zustandsautomat mit allen zulässigen und unzulässigen Übergängen, Regeln R-01 bis R-15, MC-Logik
2. **Schema- und Regeltests:** Jede erzeugte Nachricht wird gegen die XSD validiert und gegen die Anlage-1-Regeln geprüft, und zwar für **jede Kombination aus GF, Änderungskennzeichen und Produktgruppe**. Dazu kommen Golden-File-Tests mit Beispiel-XMLs.
3. **WS-Security-Interop-Tests:** von uns signierte Nachricht → Prüfung mit unabhängiger Implementierung (xmlsec1/Santuario CLI) und umgekehrt. Manipulierte Nachrichten müssen abgewiesen werden, ebenso falsche Zertifikate und eine falsche Schlüssellänge.
4. **Integrationstests:** SOAP-Ein- und Ausgang mit Testcontainers MariaDB. Outbox-Reihenfolge unter Last und Parallelität. Retry, Timeout (keine TEQ in 10 s), TEQ ≠ OK, Dubletten.
5. **Szenario-Tests aus der KFT-Testfallliste ([Anhang A](ANHANG-A-Testfaelle.md)):**
   - Jeder TF mit Scope `PFLICHT`, `PFLICHT (Scope)` und `EMPFOHLEN` wird ein automatisierter Test **mit der TF-ID im Namen** (z. B. `S_1_1_07_TAM_und_pos_TV`).
   - Ablauf: Unsere Instanz in Rolle X spricht mit dem Simulator in der Gegenrolle, und die **Meldungsfolge inkl. MC** wird geprüft.
   - Die TF-Matrix wird beim Build aus der Original-XLSX gelesen. Ein Test schlägt fehl, wenn ein TF ohne Implementierung ist (Coverage-Gate).
6. **Selbsttest beider Rollen:** Zwei Instanzen der Software (AG ↔ LE) spielen alle Szenarien gegeneinander. Das ist der beste Nachweis der Interoperabilität und läuft als Nightly-Job.
7. **Last- und Antwortzeittests:** TEQ p95 < 1 s bei 50 parallelen Anfragen, Anlagen bis 10 MB < 120 s
8. **Sicherheitstests:** TLS-Konfiguration (testssl.sh im CI gegen den Proxy), XXE und große Payloads, Auth-Bypass-Tests der REST-API, OWASP ZAP Baseline
9. **Frontend:** Vitest (Komponenten), Playwright E2E gegen den kompletten Compose-Stack

### 10.2 Bekannte Grenzen der Selbsttests

Der echte KFT-Simulator von Enghouse ist nicht öffentlich. Unser Simulator bildet ihn nach der Testfallliste nach. Abweichungen im Detail sind möglich, etwa genaue MC-Kombinationen oder welche Attribute befüllt sind. Hinweis (*1) der Testfallliste besagt, dass der KFT-Simulator **immer alle Attribute** füllt, auch optionale. Unser Simulator macht das genauso.

**Empfehlung:** Vor dem offiziellen KFT einen **Vortest bzw. Testzugang bei Enghouse** anfragen (OP-03).

### 10.3 Simulator-Funktionen

- Rolle AG oder LE, Steuerung per REST (`POST /sim/run {tfId}`) oder als Skript
- Verhält sich exakt nach der Meldungsfolge des TF: Verzögerungen, mehrfache ABM, MTAM-Timeout (verkürzbar), Dubletten-Antwort (MC 1010), TEQ(991) „überlastet“
- Bestandsaufbau (BA = X) automatisch inklusive Namenskonvention `#<extAuftragsnr>`
- Protokoll je Lauf als JUnit-XML und HTML-Report. Den können wir auch zur KFT-Vorbereitung an Enghouse geben.

## 11. Arbeitspakete und Meilensteine

| M | Inhalt | Abnahmekriterium |
|---|---|---|
| **M0** Grundgerüst | Repo, Gradle-Multimodule, CI, Compose (api, db, frontend), Flyway, Health, OpenAPI-Grundgerüst, ArchUnit-Regeln, LICENSE/README | `docker compose up` startet. CI ist grün. |
| **M1** Schema und Signatur | JAXB-Codegen, Anlage-1-Regelgenerator, WSS4J-Signatur und -Prüfung, CXF-Endpunkt `annehmenAuftrag`/`annehmenMeldung` mit TEQ, IP-Allowlist, SOAP-Archiv | Signatur-Interop-Tests grün. Ungültige Nachrichten liefern die passende TEQ. |
| **M2** Domain-Kern | Zustandsautomaten aller GF (v1), Regeln R-01 bis R-15, Meldungscode-Tabelle (Import) | Unit-Abdeckung domain ≥ 90 %, alle Übergänge getestet |
| **M3** Outbox und Inbox | Reihenfolgegarantie, Retry, Timeout-Behandlung, E-Mail-Rückfall, Zustellmonitor | Tests aus Kap. 10.1 Punkt 4 grün |
| **M4** Auth und REST | Auth-SPI, `local-users`, `api-token`, RBAC/Scopes, alle Endpunkte aus Kap. 8.5, Webhooks, Event-Feed, Setup-API | OpenAPI vollständig. Auth-Tests grün. Ein Architekturtest zeigt, dass ein Dummy-Modul ohne Kernänderung andocken kann. |
| **M5** Simulator und Szenarien | Simulator beider Rollen, alle TF aus Anhang A im Scope automatisiert, Selbsttest AG ↔ LE | **100 % der TF im Scope grün** in beiden Rollen |
| **M6** Verfügbarkeit | Replikat-Export, -Import und Delta, Online-Abfrage (LE/AG), Übernahme in Auftrag | Validierung gegen `Replikat.xsd` 1.7 und die Beispiel-XMLs aus den Anlagen |
| **M7** Frontend | alle Seiten aus Kap. 8.6, Setup-Assistent | Playwright-E2E-Suite grün. Das Frontend nutzt nur `/api/v1`. |
| **M8** Härtung und Release-Kandidat | NF-Anforderungen, Lasttests, Security-Tests, Betriebs- und Admin-Doku, Partner-Onboarding-Doku, signiertes Image, SBOM | alle NF-Tests grün, Doku vollständig, Tag `v1.0.0-rc1` |
| **M9** KFT | Termin bei Enghouse, KFT „Partner & Supplier“ V4.4 inkl. EST, Konnektivität, HomeID, Dritte (+ D-10) | Testat vorhanden. Softwarezertifikat ist beantragt und veröffentlicht. Release `v1.0.0` mit Zertifikat. |

Reihenfolge: M0 → M1 → M2 → (M3 ∥ M4) → M5 → (M6 ∥ M7) → M8 → M9

## 12. Open-Source-Projekt, Dokumentation und Arbeitsweise

Leitsatz: **Keine Blackbox.** Jede Entscheidung, jedes Verhalten an der Schnittstelle und jede Abweichung von der Spezifikation muss für fremde Entwickler nachvollziehbar sein.

### 12.1 Pflicht-Artefakte im Repository

| Datei/Verzeichnis | Inhalt | ab |
|---|---|---|
| `README.md` | Was open-spri ist, Funktionsumfang, Status, Schnellstart (Docker Compose), Installation, Konfiguration, Betrieb (Updates, Backup/Restore, Zertifikatswechsel, Monitoring), Links | M0, mit jedem Meilenstein fortgeschrieben, final in M8 |
| `docs/api/` | **Ausführliche REST-Dokumentation**: Konzepte (Auth, Tokens, Idempotenz, Paginierung, Fehler, Webhooks, Events), je Bereich eine Seite mit **curl-Beispielen** (Request und Response), Ende-zu-Ende-Abläufe („NEU-Auftrag als AG von Verfügbarkeitssuche bis ENTM“, „eingehenden Auftrag als LE bearbeiten“, „Providerwechsel als EKPab“), Changelog der API. Ergänzt die generierte OpenAPI-Datei (`docs/api/openapi.yaml`, Redoc/Swagger-UI unter `/api/docs`). | M4 |
| `docs/grundlagen/` | **Einarbeitung in S/PRI für Entwickler**: Rollen, Geschäftsfälle, Meldungsfolgen als Sequenzdiagramme (Mermaid), Zustandsautomaten je GF, Glossar, Zuordnung der Spezifikationskapitel zum Code | M2 |
| `docs/betrieb.md`, `docs/partner-onboarding.md` | Betriebshandbuch, Anbindung eines neuen Partners (Zertifikate, IPs, WSDL, Testlauf) | M8 |
| `docs/adr/` | Architecture Decision Records (`ADR-NNNN-titel.md`). Jede Grundsatzentscheidung bekommt einen Eintrag mit Kontext, Entscheidung und Konsequenzen. D-01 bis D-14 sind die Ausgangsbasis. | M0 |
| `docs/spec-abweichungen.md` | Jede Interpretation, Lücke oder Abweichung gegenüber der AK-Spezifikation mit Begründung und Quelle (z. B. OP-11) | laufend |
| `CHANGELOG/` | Änderungsbeschreibungen größerer Änderungen (Kap. 12.2) | ab dem 1. Commit |
| `CLAUDE.md` | Grundsatzentscheidungen und Arbeitsregeln für Entwickler und KI-Assistenten, damit das Projekt die Richtung behält | ab dem 1. Commit |
| `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `LICENSE` (MIT), Issue- und PR-Vorlagen | übliche Open-Source-Bestandteile, deutschsprachig | M0 |
| `scripts/fetch-spec.sh`, `scripts/gen-testmatrix.py` | Laden der AK-Dokumente, Erzeugen der Testfallmatrix | M0 |

### 12.2 Commits und CHANGELOG

- Jeder Commit fasst die Änderung **kurz und bündig** zusammen: Betreffzeile ≤ 72 Zeichen, Imperativ, deutsch.
- **Größere Änderungen** bekommen eine Datei `CHANGELOG/CL-NNNN-kurztitel.md` mit fortlaufender, nie wiederverwendeter Nummer. Inhalt: Motivation, Änderungen, Auswirkungen (API, DB-Migration, Konfiguration, Schnittstellenverhalten), Tests, betroffene TF-IDs bzw. Spezifikationskapitel. Der Commit verweist in der Betreffzeile darauf, z. B. `Outbox mit Reihenfolgegarantie [CL-0007]`.
- Als „größer“ gilt alles, was Verhalten an der S/PRI- oder REST-Schnittstelle, das Datenmodell, die Konfiguration, die Sicherheit oder eine Grundsatzentscheidung betrifft, sowie jeder Meilenstein. Reine Tippfehler- oder Refactoring-Commits ohne Verhaltensänderung brauchen keinen Eintrag.
- Releases fassen die CL-Einträge in den GitHub-Release-Notes zusammen (SemVer).

### 12.3 Sprache

Dokumentation, Commits, UI und Issues sind **deutsch**, denn die Zielgruppe ist der deutsche TK-Markt und die Spezifikation ist deutsch. Code-Bezeichner und Code-Kommentare sind **englisch**. Davon ausgenommen sind S/PRI-Fachbegriffe, die wie im Standard heißen (`Auftrag`-Felder aus dem XSD, Meldungstypen `QEB`, `ABM` …). Für Übersetzungen gibt es ein Glossar in `docs/grundlagen/glossar.md`.

## 13. Definition of Done (pro Arbeitspaket)

- Code ist reviewt, CI ist grün, keine kritischen oder hohen Schwachstellen (Trivy)
- Tests sind vorhanden. Testfälle mit S/PRI-Bezug nennen TF-ID bzw. Spezifikationskapitel im Testnamen.
- OpenAPI ist aktualisiert und der Frontend-Client neu generiert
- Flyway-Migration ist rückwärtskompatibel zum letzten Release
- Doku ist aktualisiert (README, `docs/api/` mit Beispielen, `docs/grundlagen/`, `docs/betrieb.md`, `docs/partner-onboarding.md`)
- Bei größeren Änderungen gibt es einen CHANGELOG-Eintrag, auf den der Commit verweist. Bei Grundsatzentscheidungen gibt es ein ADR, und CLAUDE.md ist aktualisiert.
- Keine Geheimnisse und keine echten Kundendaten im Repo oder in Testdaten

## 14. Rechtliches und Repository

- **Urheberrecht an den AK-Dokumenten:** Die PDFs, XLSX-Dateien und die Confluence-Tabelle des AK S&P **werden nicht** in das öffentliche Repo eingecheckt (`docs/spec-original/`, `docs/spec-text/` und die daraus erzeugte `docs/ANHANG-A-Testfaelle.md` stehen in `.gitignore`). Das betrifft auch die Meldungscodetabelle. Der Importer und die Tests laden sie aus einem lokalen Pfad bzw. aus einem CI-Secret oder Cache. Ein Skript `scripts/fetch-spec.sh` lädt sie von ak-spri.de. Ob die **XSD/WSDL (Anlage 3)** und die **Testfallliste** im Repo bzw. als Build-Artefakt mitgeliefert werden dürfen, klären wir mit dem AK (OP-02). Bis dahin werden sie beim Build heruntergeladen und mit Prüfsumme verifiziert.
- Die WSDL verwendet die Namespaces `http://spri.telekom.de/oss/v4/...`. Diese Namespaces **müssen** unverändert bleiben, nur der Service-Endpoint wird angepasst (Spezifikation 3.3).
- Marken: „S/PRI“ wird nur beschreibend verwendet. Die Software trägt einen eigenen Namen (OP-07).

## 15. Offene Punkte und Vorbedingungen

| ID | Punkt | Verantwortlich | Blockiert |
|---|---|---|---|
| OP-01 | Anfrage an den AK S&P: Softwarezertifikat für MIT/Open Source, Umgang mit Forks (Kap. 3.2) | Auftraggeber | M9 |
| OP-02 | Anfrage an den AK: Dürfen XSD/WSDL und Testfallliste in einem MIT-Repo weitergegeben werden? | Auftraggeber | M0 (Workaround: Download) |
| OP-03 | Enghouse: KFT-Ablauf, Kosten, Vorlaufzeit, Vortest bzw. Testzugang, benötigte Angaben (Endpunkte, IPs, Zertifikate) | Auftraggeber | M8/M9 |
| ~~OP-04~~ | ~~Meldungscodetabelle exportieren~~. **Erledigt am 2026-11-01**, CSV liegt vor, Format siehe R-13 | – | – |
| OP-05 | Für die Online-Verfügbarkeitsabfrage gibt es keinen AK-Standard. Unser Format (Kap. 5.4) wird als Vorschlag dokumentiert und ggf. beim AK eingebracht. | Entwickler | – |
| OP-06 | Bestätigung der Vorschläge D-10 bis D-14 | Auftraggeber | Freigabe |
| ~~OP-07~~ | ~~Produktname und Repo~~. **Erledigt:** `open-spri`, github.com/RcRaCk2k/open-spri | – | – |
| OP-08 | Die XSD 4.00.03 stammt von 2014. Abgleich mit Anlage 1 V4.4: Welche V4.4-Attribute (Konnektivität, Seriennummer ONT, Medienkonverter …) laufen über generische Attributstrukturen? Das Ergebnis wird als Mapping-Tabelle dokumentiert. | Entwickler | M1 |
| OP-09 | Öffentliche Test-Infrastruktur für den KFT: feste IPv4, DNS, TLS-Zertifikat, Hosting | Auftraggeber | M9 |
| OP-11 | Fehlende Meldungscodes 1089 und 7501, TEQ-Fehlercode für Signaturfehler: beim AK erfragen. Bis dahin gelten die Annahmen aus R-13 und Kap. 5.3. | Auftraggeber | M1/M2 (Annahme reicht) |
| OP-12 | Copyright-Inhaber für die MIT-`LICENSE` (Firma, Person oder „open-spri contributors“) und Security-Kontakt (E-Mail) für `SECURITY.md` | Auftraggeber | Release |
| OP-10 | Anlage 7 (EPKs V.13 vom 09.10.2026) und Arbeitshandbuch 2.9.5 (09.10.2026) sind brandneu. Wir prüfen, ob sie Änderungen enthalten, die den KFT-Umfang betreffen. | Entwickler | M2 |

## 16. Entwicklungsfreigabe

Die Entwicklung beginnt, wenn folgende Punkte erfüllt sind:

- [ ] Scope D-01 bis D-09 bestätigt (erledigt im Gespräch am 2026-11-01)
- [ ] Vorschläge D-10 bis D-14 bestätigt oder geändert
- [x] OP-07 (Name, Repo) entschieden: open-spri
- [ ] OP-01, OP-02 und OP-03 sind **angefragt**. Antworten sind für M0 bis M7 nicht nötig.
- [x] Festgelegt, wer die Umsetzung übernimmt: **Claude Opus, alle Arbeitspakete**
- [ ] Sprachregel aus Kap. 12.3 bestätigt

**Freigegeben durch:** ______________________  **Datum:** ____________
