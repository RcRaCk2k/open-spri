# Architecture Decision Records

Jede Grundsatzentscheidung wird als `ADR-NNNN-titel.md` festgehalten. So bleibt nachvollziehbar, warum open-spri so gebaut ist, wie es ist.

Status: `vorgeschlagen`, `angenommen`, `ersetzt durch ADR-NNNN`, `verworfen`. Ein angenommenes ADR wird nicht nachträglich geändert. Wer es ändern will, schreibt ein neues ADR, das es ersetzt.

## Vorlage

```markdown
# ADR-NNNN: Titel

Status: vorgeschlagen | Datum: JJJJ-MM-TT

## Kontext
## Entscheidung
## Konsequenzen
```

## Einträge

| Nr. | Titel | Status |
|---|---|---|
| [ADR-0001](ADR-0001-ausgangsentscheidungen.md) | Ausgangsentscheidungen der Spezifikationsphase | angenommen, ergänzt durch ADR-0002 |
| [ADR-0002](ADR-0002-scope-bestaetigung-diagnose.md) | Scope bestätigt, Diagnose in v1, Betrieb hinter nginx | angenommen, D-15 ersetzt durch ADR-0003 |
| [ADR-0003](ADR-0003-all-in-one-proxy-ip-trennung.md) | All-in-One-Stack mit nginx/certbot, getrennte Inbound-/Outbound-IPs | angenommen |
| [ADR-0004](ADR-0004-technik-versionen.md) | Technikversionen: Spring Boot 4.1, JDK 25 | angenommen |
| [ADR-0005](ADR-0005-soap-pruefkette.md) | SOAP-Schicht mit eigener Prüfkette, CXF als Transport, WSS4J direkt | angenommen |
