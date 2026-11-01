# CL-0005: M1 – S/PRI-SOAP-Schnittstelle, WS-Security, TEQ, Nachrichtenarchiv

| | |
|---|---|
| Datum | 2026-11-01 |
| Meilenstein | M1 |
| Bezug | AUFGABE Kap. 5.3, 6 (NF-07, NF-09), 7.2, 7.3, 11 (M1); ADR-0005; Spezifikation 3, 10, 11.2.3; Security-Dokument V3.0 Kap. 2.5/2.6 |

## Motivation

M1 schafft die technische Schnittstelle: Partner können signierte Aufträge und Meldungen senden und bekommen eine TEQ. Wir können umgekehrt signiert senden und die TEQ des Partners prüfen.

## Änderungen

- **Neues Modul `spri-schema`:**
  - lädt Anlage 3 (XSD 4.00.03) zur Build-Zeit und prüft die SHA-256-Prüfsumme (`VerifiedDownload` in build-logic)
  - Reihenfolge: lokale Kopie, Gradle-Cache, ak-spri.de
  - erzeugt 148 JAXB-Klassen und liefert XSD und WSDL als Classpath-Ressourcen
  - Die AK-Dateien liegen weiterhin nicht im Repository (OP-02).
- **Neues Modul `spri-soap`:**
  - `SecureXml` (gehärtetes Parsing ohne DTD/XXE), `SoapEnvelope`, `SpriSchema`, `SpriJaxb`
  - `WsSecuritySigner` / `WsSecurityVerifier` (WSS4J). Die Prüfung erzwingt: genau eine Signatur über genau den Body (gegen Signature Wrapping), SHA-2, RSA ≥ 2048, gültiges und registriertes Zertifikat.
  - `InboundProcessor`: Prüfkette IP → Envelope → Signatur → XSD → Version → signaturId → Archiv mit festen TEQ-Codes
  - `TeqResponder`: signierte TEQ, Texte aus der Meldungscodetabelle
  - `SpriClient`: signiert, sendet über CXF und prüft Signatur und Schema der TEQ-Antwort. Timeouts und Egress-Proxy sind konfigurierbar.
  - `SpriEndpoint` (CXF-Provider, `/spri/v4`, WSDL unter `?wsdl`), `SourceAddressFilter` (HTTP 403 für nicht freigeschaltete IPs)
- **domain:** `Partner`, `IpNetwork` (CIDR, IPv4/IPv6), `TeqCode`, `ArchivedExchange`, Ports `PartnerDirectory`, `InboundStore`, `OutboundArchive`
- **persistence:** Migration V2 (`partner`, `partner_certificate`, `partner_source_network`, `soap_archive`, `inbox_message`), `JdbcPartnerDirectory`, `JdbcMessageArchive` (Archiv und Eingangswarteschlange in einer Transaktion)
- **app:** CXF unter `/spri`, Forwarded-Header nur von internen Proxys, Konfiguration `openspri.soap.*`
- **Betrieb:** Compose reicht Keystore, veröffentlichte Endpoint-URL und Egress-Proxy durch und bindet `./secrets` ein. `docs/betrieb.md` beschreibt Schlüsselerzeugung, Endpunkt und TEQ-Codes.
- **CI:** `xmlsec1` für den unabhängigen Signaturtest. Der Stack-Test prüft, dass `/spri/v4` ohne freigeschalteten Partner HTTP 403 liefert.
- **Doku:** ADR-0005, `docs/spec-abweichungen.md` SA-004 bis SA-007, CLAUDE.md

## Auswirkungen

- S/PRI: neuer Endpunkt `/spri/v4` (annehmenAuftrag, annehmenMeldung)
- REST-API: unverändert
- Datenbank: Migration V2
- Konfiguration: `OPENSPRI_SOAP_SIGNING_KEYSTORE`, `OPENSPRI_SOAP_SIGNING_KEYSTORE_PASSWORD`, `OPENSPRI_SOAP_PUBLISHED_ENDPOINT_URL`, `SPRI_OUTBOUND_PROXY`
- **Abweichung vom Plan:** Der Anlage-1-Regelgenerator mit Attributprüfung (Prüfschritt 4 in Kap. 5.3) wandert nach M2. Er braucht eine Zuordnung der fachlichen Attributnamen aus Anlage 1 zu den XSD-Elementen. Diese Zuordnung gehört zur fachlichen Modellierung der Geschäftsfälle.
- Ohne konfigurierten Schlüssel wird ein vorläufiger Schlüssel erzeugt (Warnung im Log). Die dauerhafte Ablage übernimmt der Setup-Assistent in M4.

## Tests

- `WsSecurityTest` (12): Gutfall, Algorithmen, unabhängige Prüfung durch JDK und `xmlsec1` (inkl. Erkennung einer Manipulation), 2048-Bit-Altzertifikat, manipulierter Body, fremdes Zertifikat, unsigniert, SHA-1, 1024 Bit, abgelaufen, Signature Wrapping
- `InboundProcessorTest` (13): jeder Prüfschritt mit TEQ-Code, signierte und schemagültige TEQ, Speicherfehler
- `SoapEndToEndTest` (7): echtes HTTP durch Tomcat und CXF für Auftrag, Meldung, negative TEQ, 403, fremde Antwortsignatur, WSDL, Schlüssellänge
- `ApplicationIntegrationTest` (CI mit MariaDB): Roundtrip mit Archiv in beide Richtungen und Eingangswarteschlange
- `IpNetworkTest` (4)
