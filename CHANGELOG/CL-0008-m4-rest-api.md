# CL-0008: M4 – REST-API, Anmeldung, Setup, Partner, Aufträge

| | |
|---|---|
| Datum | 2026-11-01 (in Arbeit) |
| Meilenstein | M4 |
| Bezug | AUFGABE Kap. 5.5–5.10, 7.3, 7.5, 8.5, 12.1 |

## Motivation

M4 macht open-spri bedienbar: Web-Oberfläche und Fremdsysteme steuern alles über eine dokumentierte REST-API (API-first, Grundsatz 4).

## Änderungen

### Teil 1: Anmeldung und Berechtigungen
- **Neue Module:**
  - `auth-api`: SPI `AuthenticationModule`, `Role`, `Scope`, `Actor`, `AuditLog`
  - `auth-token`: Long-Life-Tokens `spri_<prefix>_<geheimnis>`. Gespeichert wird nur der SHA-256-Hash, verglichen in konstanter Zeit. Ablauf, Widerruf, letzte Nutzung.
  - `auth-local`: Benutzer mit Argon2id-Passwort (mindestens 12 Zeichen). Unbekannte Benutzer kosten dieselbe Zeit wie bekannte.
- **`SecurityConfiguration`:**
  - alle Module als Beans, Sessions per Cookie (SameSite=Strict)
  - CSRF im SPA-Modus (Cookie `XSRF-TOKEN`, Header `X-XSRF-TOKEN`), Bearer-Aufrufe ausgenommen
  - Berechtigungen per `@PreAuthorize` mit `SCOPE_…`
  - öffentlich nur Health, About, OpenAPI/Swagger, Login, Setup, `/spri` und `/replikat` (eigener Schutz)
- **REST:**
  - `POST /auth/login`, `POST /auth/logout`, `GET /auth/me`, `GET /auth/modules`
  - `GET/POST/DELETE /tokens` (Geheimnis nur einmal in der Antwort)
  - `GET/POST/PATCH /users`
- Fehlerantworten nach RFC 9457 (`ProblemHandlers`), Ablehnungen mit `reasons`
- Migration V6: `app_user`, `api_token`, `audit_log`
- **Korrektur:** Die IP-Sperre von `/spri` setzt den Status 403 direkt. `sendError` hätte über `/error` die REST-Sicherheit durchlaufen und 401 geliefert.

### Teil 2: Setup-Assistent und Einstellungen
- `SetupGuard`: Solange das Setup nicht abgeschlossen ist, steht ein einmaliges Setup-Token im Container-Log. Der Vergleich läuft in konstanter Zeit, nach dem Abschluss ist das Setup gesperrt (409).
- `POST /api/v1/setup`: Admin-Benutzer, Firmendaten (ICC, Rollen, Optionen, WBCI-Modus, öffentliche Endpunkt-URL) und Signaturschlüssel (neu erzeugen oder PKCS#12 übernehmen) in **einer** Transaktion. `GET /api/v1/setup` liefert den Stand.
- `GET/PUT /api/v1/settings`, `GET/POST /api/v1/settings/signing-key` (Zertifikat als PEM für Partner, Schlüsselwechsel ohne Neustart)
- **Signaturschlüssel:**
  - `SigningKeyHolder` (aktueller Schlüssel, zur Laufzeit austauschbar), `CachingSigner` (TEQ-Antworten und Client signieren immer mit dem aktuellen Schlüssel)
  - `KeyEncryption` (AES-256-GCM mit `OPENSPRI_MASTER_KEY`, der nie in der Datenbank liegt), `SigningKeyService`
  - Reihenfolge beim Start: Keystore-Datei, dann gespeicherter Schlüssel, dann vorläufiger Schlüssel mit Warnung
- Fachmodell `CompanySettings` (ICC nach ITU M.1400), `WbciMode`. Migration V7: `company_settings`, `signing_key`.
- Compose und `.env.example`: `OPENSPRI_MASTER_KEY`. `docs/betrieb.md`: Abschnitt Ersteinrichtung.
- **Korrektur während der Entwicklung:** Ein `Optional<…>`-Bean-Parameter wird von Spring als optionale Abhängigkeit auf den inneren Typ aufgelöst. Daher gibt es jetzt den eigenen Typ `MasterKey`.

### Teil 3: Partnerverwaltung
- Port `PartnerAdministration` (in `JdbcPartnerDirectory` umgesetzt): auflisten (auch deaktivierte), anlegen, ändern, Zertifikate hinzufügen/entfernen (mehrere während eines Wechsels), IP-Netze ersetzen
- **REST:**
  - `GET/POST /partners`, `GET/PATCH /partners/{id}`, `PUT /partners/{id}/source-networks`, `POST/DELETE /partners/{id}/certificates`
  - PEM-Prüfung, CIDR ohne Hostnamen, nur http(s)-Endpunkte
  - Zertifikate mit Schlüssellänge und Ablaufkennzeichen
- `GET /partners/wsdl`: AK-WSDL mit unserem öffentlichen Endpunkt (Spezifikation 3.3: nur der Endpoint wird angepasst)
- `GET /partners/onboarding-sheet`: ICC, Endpunkt, WSDL, Outbound-IPs, Signaturzertifikat (PEM), Kontakt, S/PRI-Version
- Einstellungen: `outboundAddresses` (Migration V8)

### Teil 4: Aufträge, Meldungen, Zustellung
- **`SpriJson`** (spri-soap): generischer Konverter zwischen JSON und den JAXB-Klassen der AK-XSD. Feldnamen wie im XSD, abstrakte Typen mit `"@type": "<XSD-Typname>"`, Fehler mit JSON-Pfad und erlaubten Feldnamen. Es gibt kein zweites, eigenes Auftragsmodell (keine Blackbox).
- **`OutboundPayloads`:** baut eigene Aufträge und Meldungen, setzt den `control`-Kopf (Zeitstempel, Version, signaturId des aktuellen Schlüssels) und prüft jedes Ergebnis gegen XSD und Anlage 1 (`enforce`), bevor es in den Postausgang darf.
  - Storno und Terminverschiebung werden aus dem ursprünglichen Auftrag im Postausgang abgeleitet (5.1.3/5.1.4).
  - Eine Reklamation ist ein Entstörauftrag mit `reklamation` (6.1.5).
  - Meldungen in vereinfachter Form: Typ, Codes, optionale `meldungsattribute`. Den Kopf, die externe Auftragsnummer, die Auftraggebernummer und Texte aus der Meldungscodetabelle ergänzt open-spri (SA-011).
- **`OrderCommands`:** Einstieg für die REST-API, nutzt `OutboundOrderService` (Zustandsautomat, R-13, Outbox). Eine Meldung wird **vor** dem Zusammenbau gegen Rolle, Codes und Automat geprüft, damit eine unzulässige Aktion mit 409 und `allowedActions` abgelehnt wird statt mit Inhaltsfehlern.
- `OrderActionRejected` trägt die zulässigen Aktionen (Grundsatz 6). `OutboundOrderService.allowedActions` filtert die Übergänge des Automaten nach unserer Rolle.
- **Lese-Ports** im Fachmodell (`domain.query`): `OrderQueries`, `DeliveryQueries`, `InboxQueries`, `ArchiveQueries`, umgesetzt in den JDBC-Klassen. Die Suche maskiert LIKE-Platzhalter.
- **REST:**
  - `GET /orders` (Filter, Präfixsuche, Cursor), `GET /orders/{id}` mit `allowedActions`, `GET /orders/{id}/history`
  - `POST /orders`, `POST /orders/{id}/requests`, `POST /orders/{id}/messages` (202; die Zustellung läuft asynchron)
  - `GET /deliveries`, `GET /deliveries/{id}`, `GET /deliveries/{id}/payload`, `POST /deliveries/{id}/retry|sent-by-email|cancel`
  - `GET /inbox`, `POST /inbox/{id}/reprocess`
  - `GET /archive/{id}`, `GET /archive/{id}/request|response` (byte-genau; Zugriff auf Inhalte wird protokolliert)
  - `GET /message-codes`, `POST /message-codes` (neue Version importieren, sofort aktiv)
  - Fehler: 400 mit `errors` (XSD, Anlage 1, JSON-Pfad), 404, 409 mit `reasons` und `allowedActions`
- Meldungen als Leistungserbringer brauchen den Scope `le:messages:write`, alles andere `orders:write`.

### Teil 4b: Ereignisse, Webhooks, Idempotenz, Pfade nach AUFGABE 8.5
- **Pfade angeglichen** an AUFGABE 8.5: `POST /orders/{id}/cancel|reschedule|complaint` statt `/requests`, `GET /orders/{id}/allowed-actions`, Archiv unter `GET /messages/{id}` und `/messages/{id}/raw`, `POST /message-codes/import`.
- **Verlauf mit Richtung:** `OrderHistoryEntry.direction` (INBOUND/OUTBOUND), im REST-Verlauf als `direction`. Migration V9 füllt Altbestände (ohne Archiv-Id = ausgehend).
- **Ereignisse (D-14):** `BusinessEvent` und `EventLog` im Fachmodell. Jeder Verlaufseintrag (Auftrag, Änderung, Meldung, ein- und ausgehend) und jede Statusänderung im Postausgang wird **in derselben Transaktion** zum Ereignis: `order.received`, `request.received`, `message.received`, `order.submitted`, `request.submitted`, `message.submitted`, `delivery.updated`. Keine Endkundendaten (Grundsatz 13), Aufbewahrung `OPENSPRI_EVENTS_RETENTION` (90 Tage).
  - `GET /events?after=&types=`: Ereignisse erscheinen erst nach 2 s (Datenbankzeit), weil AUTO_INCREMENT-Ids in anderer Reihenfolge committen können, als sie vergeben wurden. So überspringt ein Cursor kein Ereignis.
- **Webhooks:** `GET/POST/PUT/DELETE /webhooks`. Das Geheimnis (`whsec_…`) wird nur bei der Anlage angezeigt und AES-256-GCM-verschlüsselt gespeichert. `WebhookDispatcher` (Fachmodell) stellt in Feed-Reihenfolge zu, ein Ereignis pro Anfrage, Typfilter, Backoff 10 s verdoppelt bis 1 h. Bei einem Fehler wartet die Folge. `WebhookPoster` signiert mit `X-OpenSpri-Signature: t=<s>,v1=<HMAC-SHA256("t.body")>` und folgt keinen Weiterleitungen. Mehrere Instanzen sperren einen Empfänger per `FOR UPDATE SKIP LOCKED`.
- **Idempotenz:** `IdempotencyFilter` für `POST /orders…` mit Header `Idempotency-Key`. Schlüssel je Benutzer/Token, Hash über Methode, Pfad und Body. Eine Wiederholung bekommt die gespeicherte 2xx-Antwort (`Idempotent-Replayed: true`). Derselbe Schlüssel für eine andere Anfrage ergibt 422, ein noch laufender 409. Nach einem Fehler ist der Schlüssel wieder frei. Aufbewahrung 24 h. Login, Tokens und Setup sind ausgenommen, weil ihre Antworten Geheimnisse enthalten.
- **`DeliveryOperations`** (Fachmodell): Eingriffe im Postausgang erzeugen Ereignisse. **Korrektur:** Ein Auftrag im Status `UNCLEAR` lässt sich nicht mehr erneut senden (NF-05, 11.2.3.1). Er wird mit neuer Nummer neu gestellt und der alte Eintrag zurückgezogen.
- Migration V9: `spri_order_history.direction`, `event`, `webhook_subscription`, `idempotency_key`.

### Teil 5: API-Dokumentation
- `docs/api/README.md`: Übersicht, Konventionen (Fehler, Scopes, Idempotenz, Cursor, asynchrones Senden), Fehlerformat mit `reasons` und `allowedActions`
- je Bereich eine Seite mit curl-Beispielen und Antworten: `setup.md`, `auth.md` (inkl. Rollen- und Scope-Tabelle), `partner.md`, `auftraege.md` (JSON-Format nach XSD, alle Rollen), `zustellung.md`, `ereignisse.md` (Webhook-Signatur mit Prüfbeispiel in Shell und Python), `meldungscodes.md`
- `ablaeufe.md`: NEU als Auftraggeber bis ENTM über den Event-Feed, eingehenden Auftrag als Leistungserbringer bearbeiten, Zustellproblem beheben
- `docs/betrieb.md`: Zustellmonitor per REST, Abschnitt Ereignisse und Webhooks

### Teil 6: Entwürfe, Kündigung durch den Leistungserbringer, Providerwechsel-Anfragen
- **Entwürfe (AUFGABE 5.6, 8.5):** neue Phase `DRAFT` und Ereignis `OrderEvent.Send` im Zustandsautomaten. Im Entwurf ist nur Senden zulässig (`allowedActions` = `SEND`). **Verhaltensänderung:** `POST /orders` legt jetzt einen Entwurf an (201). `?send=true` sendet wie bisher sofort (202).
  - `GET /orders/{id}/draft`, `PUT /orders/{id}`, `DELETE /orders/{id}`, `POST /orders/{id}/send`
  - Ein Entwurf ist ein gültiger Auftrag (XSD, Anlage 1) und reserviert die Auftragsnummer. Die Auftragsregeln werden beim Senden geprüft, der Kopf wird dann mit aktuellem Schlüssel neu gebaut.
  - Migration V10: `spri_order_draft` (eingegebenes JSON). `JdbcOrderRepository.save` schreibt jetzt alle Spalten, weil ein Entwurf jeden Inhalt ändern darf.
- **`POST /terminations` (KUE-LE):** legt den Auftrag in der Rolle `LEISTUNGSERBRINGER` an und sendet die KDLE (`OutboundOrderService.terminate`). Die Nummer vergibt der Leistungserbringer (Anlage 1). Geprüft wird vor dem Speichern, eine abgewiesene KDLE hinterlässt keinen Auftrag.
- **`GET /pv-requests`, `POST /pv-requests/{id}/reply`:** AKM-PV als abgebender EKP mit Antwortfrist, aufnehmendem Provider und bisheriger LineID aus der archivierten AKM-PV. Die RUEM-PV übernimmt Vertrags- und Auftragsnummern, VorabstimmungsID und `anschluss` aus der AKM-PV (SA-012).
- Idempotenz gilt jetzt auch für `/orders/{id}/send`, `/terminations` und `/pv-requests/{id}/reply`.

### Noch offen in M4
- Partner: `/contracts`, `/powers-of-attorney`, `/projects`; `CRUD /products` und `/product-change-matrix`; `POST /maintenance-announcements`
- Bestand, Diagnose, Verfügbarkeit, Replikat: mit M5/M6, weil die Fachlogik dort entsteht

## Tests
- Teil 6: `OrderLifecycleTest` (Entwurf: nur Senden), `OutboundOrderServiceTest` (Entwurf reserviert die Nummer, wird erst beim Senden eingereiht, bleibt bei Regelverstoß Entwurf, Löschen gibt die Nummer frei; KDLE legt KUE-LE an, keine Spuren bei Abweisung, ERLM folgt), `OrderApiTest` (Entwurf anlegen/ändern/senden/löschen, 409 nach dem Senden; KDLE mit Scope und Dublette; AKM-PV mit Frist → RUEM-PV mit Feldern aus der AKM-PV, zweite RUEM-PV 409), `ApplicationIntegrationTest` (Entwurf ändert alle Spalten, Löschen samt Inhalt, Filter nach Übergabephase)

- `SpriJsonTest` (3): XML → JSON → XML für Auftrag und QEB bleibt XSD-gültig, Fehlermeldungen mit Pfad und Vorschlägen
- `OutboundPayloadsTest` (4): Auftrag mit unserem Kopf, ungültiges JSON mit Pfad, Storno/Terminverschiebung aus dem Original, vereinfachte QEB
- `OrderApiTest` (4, echtes HTTP): Auftrag → Postausgang → Storno → zweites Storno 409 mit `allowedActions` → Zustellung zurückziehen; 400/404/409 bei ungültigem Inhalt, unbekanntem Partner und doppelter Nummer; QEB als Leistungserbringer (403 ohne Scope, R-13, ERLM vor ABM → 409 mit ABM als erlaubter Aktion); Meldungscodetabelle
- `ApplicationIntegrationTest` (MariaDB, CI): Lese-Ports inklusive LIKE-Maskierung, Verlauf, Original-Payload, Archiv; Ereignisse erst nach der Verzögerung sichtbar, Typfilter, Webhook-Cursor unter Sperre, Idempotenz-Schlüssel (laufend, abweichend, Wiederholung, je Akteur)
- `EventApiTest` (2, echtes HTTP): Auftrag → `order.submitted` im Feed → Webhook an lokalen HTTP-Server, Signatur nachgerechnet, Geheimnis nur einmal sichtbar, ungültige URL/Typen 400; Idempotenz: Wiederholung ohne zweiten Versand, anderer Body 422, nach Fehler wieder frei
- `WebhookDispatcherTest` (3): Reihenfolge und Typfilter, Fehler hält den Cursor vor dem Ereignis und wartet (Backoff), Backoff-Kurve
- `DeliveryOperationsTest` (2): `UNCLEAR`-Auftrag nicht erneut senden (NF-05), zurückziehen erzeugt `delivery.updated`; erneut senden bei `UNDELIVERABLE` und unklarer Meldung
- `InboundOrderServiceTest`: jeder Verlaufseintrag wird Ereignis, auch zu unbekanntem Auftrag
- `OrderApiTest`: zusätzlich Terminverschiebung mit `terminNeu`, Reklamation ohne `reklamation` → 400, `allowed-actions`, Richtung im Verlauf
- `PartnerApiTest` (4): Anlage mit Zertifikat und IP-Netzen, Zertifikatswechsel (hinzufügen, alt entfernen), ungültiges PEM/Hostname/URL → 400, VIEWER darf lesen, nicht ändern; WSDL mit unserem Endpunkt, Onboarding-Blatt
- `SetupTest` (3): vor dem Setup Token nötig (403), Setup mit Token legt Admin, Firma und gespeicherten Schlüssel (3072 Bit) an, Login und Einstellungen funktionieren, kein zweites Setup (409)
- `SecurityTest` (6, echtes HTTP): öffentliche Endpunkte, 401 ohne Anmeldung, Token mit Berechtigung und 403 ohne, ungültiges und widerrufenes Token, Berechtigungen nicht über die Rolle hinaus, Session-Login mit CSRF-Schutz (ohne Token 403, mit Token 201)
