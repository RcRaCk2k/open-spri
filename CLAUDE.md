# CLAUDE.md – Grundsätze für open-spri

Diese Datei gilt für alle, die an open-spri arbeiten: Menschen und KI-Assistenten. Sie hält die Grundsatzentscheidungen fest, damit das Projekt die Richtung behält. Wer davon abweichen will, schreibt zuerst ein ADR (`docs/adr/`) und passt danach diese Datei an. Eine stille Abweichung ist nicht erlaubt.

## Worum es geht

open-spri ist eine MIT-lizenzierte Implementierung der **S/PRI-Schnittstelle V4.4** des Arbeitskreises Schnittstellen & Prozesse (AK S&P, https://ak-spri.de). Sie umfasst alle Geschäftsfälle einschließlich EST und DIAG, und sie deckt **beide Rollen** ab: Auftraggeber (Leitungen bei Partnern bestellen) und Leistungserbringer (eigene Leitungen anbieten). Dazu kommen eine öffentliche REST-API, ein Web-Frontend, Replikate und eine Online-Verfügbarkeitsabfrage. Ziel ist ein Softwarezertifikat des AK nach erfolgreichem Konformitätstest (KFT) bei Enghouse.

- Maßgebliche Aufgabenbeschreibung: `docs/AUFGABE-SPRI.md`. Sie ist die einzige verbindliche Quelle für Scope und Anforderungen.
- KFT-Testfälle: `docs/ANHANG-A-Testfaelle.md`, lokal erzeugt mit `scripts/gen-testmatrix.py`
- AK-Originaldokumente: `scripts/fetch-spec.sh` lädt sie nach `docs/spec-original/`. Sie werden **nie committen**, weil sie urheberrechtlich geschützt sind.

## Grundsätze

1. **Keine Blackbox.** Jedes Verhalten an der Schnittstelle muss sich im Code, in Tests und in der Doku nachvollziehen lassen. Interpretationen der Spezifikation und Lücken darin stehen in `docs/spec-abweichungen.md`, jeweils mit Quelle (Dokument, Kapitel bzw. TF-ID).
2. **Die AK-Spezifikation ist normativ.** Bei einem Widerspruch zwischen unserem Code bzw. unserer Doku und der Spezifikation gewinnt die Spezifikation. Den Widerspruch dokumentieren, nichts „verbessern“. XSD-Namespaces (`http://spri.telekom.de/oss/v4/...`) und WSDL-Operationen bleiben unverändert.
3. **Die Fachlogik ist protokollneutral.** Das Modul `domain` hängt von keinem Framework ab, also kein Spring, CXF, JAXB oder JPA. SOAP (`spri-soap`) ist ein Adapter. Ein späterer FIT-Adapter (Nachfolger der AK-Schnittstellen) muss dieselbe Fachlogik nutzen können. ArchUnit erzwingt das.
4. **API-first, ohne private Endpunkte.** Das Frontend benutzt ausschließlich die öffentliche REST-API `/api/v1`. Was das Frontend kann, können externe Systeme auch. Jede neue Funktion beginnt mit der OpenAPI-Beschreibung.
5. **Authentifizierung ist modular.** Neue Verfahren (OIDC, SAML, LDAP) kommen als Modul über die SPI `AuthenticationModule` dazu, ohne Kernänderung. Autorisierung läuft über Rollen und Scopes und wird im Service-Layer geprüft.
6. **Zustandsautomaten sind explizit.** Jeder Geschäftsfall ist ein expliziter Automat. Unzulässige Übergänge werden abgewiesen (REST: 409 mit erlaubten Aktionen). Eine Meldung ohne Prüfung gegen den Automaten zu senden ist nicht möglich.
7. **Zustellung ist verlässlich.** Ausgehende Nachrichten laufen nur über die transaktionale Outbox, mit strenger FIFO-Reihenfolge je Reihenfolgeschlüssel (`partner + lineId`, sonst `partner + externe Auftragsnummer`). Direkte SOAP-Aufrufe aus der Fachlogik gibt es nicht.
8. **Der Eingang ist schnell und dauerhaft.** Eingehende SOAP-Nachrichten werden synchron geprüft (IP, Signatur, Version, XSD, Anlage-1-Regeln), archiviert und mit TEQ quittiert (Ziel p95 < 1 s, Pflicht < 10 s). Die fachliche Verarbeitung läuft danach asynchron.
9. **Sicherheit ist nicht verhandelbar.** TLS ≥ 1.2 mit Whitelist-CA. WS-Security-Signatur mit RSA ≥ 3072 und SHA256. IP-Allowlist je Partner. XML ist gehärtet (kein XXE). Geheimnisse liegen nie im Repo, in Logs oder unverschlüsselt in der DB. API-Tokens werden nur als Hash gespeichert.
10. **Regeln sind Daten.** Die Attributregeln aus Anlage 1 und die Meldungscodes aus Anlage 5 werden aus den Quelldateien generiert bzw. importiert und versioniert. Sie werden nicht von Hand in den Code geschrieben.
11. **Die Testfallliste ist das Abnahmekriterium.** Jeder KFT-Testfall im Scope wird ein automatisierter Szenario-Test mit der TF-ID im Namen (`S_1_1_07_...`). Ein Coverage-Gate lässt den Build scheitern, wenn ein TF ohne Test ist. Vor einem KFT-Termin müssen alle TF im eigenen Simulator grün sein.
12. **Einfacher Betrieb.** Docker Compose mit den Containern `api`, `db` (MariaDB) und `frontend`. Dazu kommen standardmäßig `proxy` (nginx) und `certbot` (Let's Encrypt), abschaltbar für einen externen Reverse-Proxy (ADR-0003), sowie `spri-simulator` (nur Test/Dev). Es gibt keine zusätzlichen Infrastruktur-Abhängigkeiten wie einen Message-Broker, ohne ADR.
13. **Datenschutz.** Endkundendaten sind personenbezogen: Daten sparsam erheben, Aufbewahrung konfigurierbar, Zugriffe auditieren. In Tests und Beispielen stehen nur erfundene Daten.

## Technik (festgelegt, Änderung nur per ADR)

- Kotlin 2.x auf JDK 21, Spring Boot 3, Spring Security 6, Apache CXF 4 + WSS4J, JAXB-Codegen aus Anlage-3-XSD
- MariaDB 11 LTS, Flyway (Migrationen nur additiv bzw. rückwärtskompatibel)
- Gradle Kotlin DSL, Multi-Module (Struktur siehe `docs/AUFGABE-SPRI.md`, Kap. 8.2)
- Frontend: React + TypeScript (strict), Vite, pnpm. API-Client wird aus OpenAPI generiert, keine handgeschriebenen URLs.
- Tests: JUnit 5, Testcontainers, WireMock, ArchUnit, Playwright, k6 oder Gatling

## Arbeitsweise

- **Sprache:** Doku, Commits, UI und Issues auf Deutsch. Code-Bezeichner und Kommentare auf Englisch. S/PRI-Fachbegriffe bleiben wie im Standard (`QEB`, `ABM`, `Auftraggeber`-Felder des XSD).
- **Commits:** kurz und bündig, Betreff ≤ 72 Zeichen, Imperativ, deutsch.
- **CHANGELOG:** Größere Änderungen bekommen eine Datei `CHANGELOG/CL-NNNN-kurztitel.md` (fortlaufende Nummer, Vorlage in `CHANGELOG/README.md`). Der Commit verweist im Betreff darauf: `… [CL-NNNN]`. „Größer“ heißt: Verhalten an der S/PRI- oder REST-Schnittstelle, Datenmodell, Konfiguration, Sicherheit, Grundsatzentscheidung oder Meilenstein.
- **ADR:** Jede Grundsatzentscheidung bekommt `docs/adr/ADR-NNNN-titel.md`, und diese Datei wird angepasst.
- **Doku gehört zur Änderung:** README (Installation, Betrieb), `docs/api/` (REST mit curl-Beispielen), `docs/grundlagen/` (S/PRI-Einführung) werden im selben PR aktualisiert.
- **Tests gehören zur Änderung:** Fachlogik ohne Unit-Tests und Schnittstellenverhalten ohne Szenario-Test werden nicht gemergt.

## Was nicht erlaubt ist

- AK-Dokumente, die Meldungscodetabelle oder die Testfallliste committen
- Fachlogik in Controller, SOAP-Endpunkte oder Repositories schreiben
- Den Frontend-Zugriff an der öffentlichen API vorbei bauen
- Meldungen ohne Outbox versenden oder die Reihenfolge parallelisieren
- Validierung abschalten oder lockern, „weil ein Partner es so schickt“. Dafür gibt es stattdessen eine partnerbezogene, dokumentierte Ausnahme mit ADR.
- Echte Kunden-, Partner- oder Zertifikatsdaten in Tests, Logs oder Beispielen verwenden

## Befehle

Wird in M0 ergänzt (Build, Tests, Simulator-Suite, lokaler Stack).
