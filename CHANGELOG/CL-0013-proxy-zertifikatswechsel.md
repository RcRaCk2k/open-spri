# CL-0013: Proxy übernimmt das Let's-Encrypt-Zertifikat nach Sekunden

| | |
|---|---|
| Datum | 2026-11-03 |
| Meilenstein | nach M8 (Erfahrung aus der ersten Installation auf Debian 13) |
| Bezug | AUFGABE 7.4; `deploy/proxy/40-openspri-tls.sh`; docs/betrieb.md |

## Motivation
Bei der ersten Installation und nach jeder Änderung von `PUBLIC_HOSTNAME` hatte certbot das Zertifikat nach wenigen Sekunden. Der Proxy lieferte trotzdem bis zu 5 Minuten lang das vorläufige selbstsignierte Zertifikat aus, weil er nur alle 5 Minuten nachsah. Browser und curl meldeten in dieser Zeit „selbstsigniert“, obwohl alles funktionierte.

## Änderungen
- Solange das vorläufige Zertifikat aktiv ist, prüft der Proxy alle 10 Sekunden (`CERT_WAIT_INTERVAL`). Danach prüft er wie bisher alle 5 Minuten (`CERT_CHECK_INTERVAL`) auf ein verlängertes Zertifikat.
- docs/betrieb.md:
  - neuer Abschnitt „Hostnamen ändern“: `docker compose up -d` statt `restart`, DNS vorher setzen, Endpunkt in den Einstellungen nachziehen
  - neue Hinweise zu ECMP und SNAT mit nftables, zu `flush ruleset` und zu IPv6 im Docker-Netz

## Auswirkungen
- S/PRI-Schnittstelle: keine
- REST-API: keine
- Datenbank (Migration): keine
- Konfiguration: optional `CERT_WAIT_INTERVAL` im Container `proxy`
- Betrieb/Sicherheit: Das echte Zertifikat ist nach dem Start schneller aktiv.

## Tests
Manuell: Ein nginx-Container mit dem Skript startet ohne Zertifikat. Ein nach dem Start abgelegtes Zertifikat war 3 Sekunden später aktiv und nginx neu geladen. Die CI prüft den Proxy weiter im Compose-Job (TLS, testssl.sh).
