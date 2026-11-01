# ADR-0005: SOAP-Schicht mit eigener Prüfkette, CXF als Transport, WSS4J direkt

Status: angenommen | Datum: 2026-11-01 | Bezug: D-09, AUFGABE Kap. 5.3, 7.2

## Kontext

Die Spezifikation verlangt (11.2.3.1): Jeder eingehende Auftrag und jede Meldung wird mit einer **technischen Quittung (TEQ)** beantwortet. Das gilt auch, wenn Signatur, Schema oder Version nicht stimmen. Im Fehlerfall enthält die TEQ nur eine Fehlernummer.

Die Standard-Security-Interceptors von CXF (WSS4JInInterceptor, Policy-Verarbeitung) antworten bei Signaturfehlern mit einem **SOAP-Fault**, nicht mit einer TEQ. Außerdem verbirgt die Policy-Verarbeitung, welche Prüfungen in welcher Reihenfolge laufen. Das widerspricht dem Grundsatz „Keine Blackbox“.

## Entscheidung

- **Apache CXF bleibt Transport** (D-09):
  - SOAP 1.1 über HTTP
  - Veröffentlichung der AK-WSDL unter `/spri/v4?wsdl`, Endpoint-Adresse ersetzt
  - Client mit Timeouts und Egress-Proxy

  Der Endpunkt ist ein JAX-WS-`Provider` im MESSAGE-Modus. CXF reicht die **exakten Bytes** der Anfrage durch (`RawRequestInterceptor`).
- **Eigene, sichtbare Prüfkette** `InboundProcessor`: IP → Envelope → Signatur → XSD → Version → signaturId → Archiv und Eingangswarteschlange. Jeder Schritt hat einen festen TEQ-Code und einen eigenen Test.
- **WSS4J (Apache, DOM-API) direkt** für Signieren und Prüfen (`WsSecuritySigner`, `WsSecurityVerifier`):
  - Algorithmen fest SHA256withRSA, SHA-256 und exklusive Kanonisierung, BinarySecurityToken als Direktreferenz
  - Beim Prüfen werden zusätzlich erzwungen: genau eine Signatur, Schutz genau dieses Body-Elements (gegen Signature Wrapping), nur SHA-2 und die Mindestschlüssellänge
- **OpenSAML ausgeschlossen:** WSS4J zieht OpenSAML als Abhängigkeit, wir brauchen kein SAML, und OpenSAML liegt nicht auf Maven Central.
- **Datenbankzugriff mit Spring `JdbcClient`** und explizitem SQL. AUFGABE 8.2 hatte jOOQ empfohlen und die Wahl freigestellt. Explizites SQL ist ohne Code-Generator und ohne JPA-Magie direkt lesbar.
- **Die JAXB-Klassen werden aus der Original-XSD erzeugt.** Die Paketnamen folgen den Namespaces (`de.telekom.spri.oss.v4.*`), damit die Zuordnung zur Spezifikation eindeutig bleibt.

## Konsequenzen

- Interoperabilität wird durch Tests nachgewiesen: Unsere Signaturen werden vom JDK (`javax.xml.crypto`) und von `xmlsec1` (libxmlsec) unabhängig bestätigt. Manipulationen erkennen beide.
- Neue Prüfungen (Anlage-1-Attributregeln, Dubletten ab M2) werden als Schritte in der Prüfkette bzw. in der fachlichen Verarbeitung ergänzt. CXF-Security-Interceptors werden nicht eingesetzt.
