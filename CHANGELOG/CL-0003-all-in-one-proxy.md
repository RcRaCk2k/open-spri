# CL-0003: All-in-One-Stack mit nginx/certbot, getrennte Inbound-/Outbound-IPs

| | |
|---|---|
| Datum | 2026-11-01 |
| Meilenstein | vor M0 (Spezifikationsphase) |
| Bezug | docs/AUFGABE-SPRI.md Kap. 4 (D-15), 7.4, 7.6, 8.1, 12.1, OP-09; ADR-0003 |

## Motivation

open-spri soll als einfaches Startpaket ohne vorhandene Infrastruktur laufen. Außerdem war zu klären, ob Anfragen an Partner von einer anderen IP ausgehen dürfen als der, unter der wir erreichbar sind.

## Änderungen

- D-15 neu gefasst: `proxy` (nginx) und `certbot` gehören standardmäßig zum Stack, ein externer Proxy bleibt optional.
- Kap. 7.4 neu: Ablauf der Zertifikatsausstellung inkl. Übergangszertifikat, Schlüsseltyp ECDSA P-256 fest, Staging-Modus, nginx-Vorgaben, Varianten A/B, TLS-Selbsttest
- Kap. 7.6 neu: Trennung der Inbound- und Outbound-IPs, SNAT bzw. Egress-Proxy, Onboarding-Blatt für Partner, Selbsttest der Outbound-IP
- Kap. 8.1: Containerbild aktualisiert. Kap. 12.1: Verzeichnis `deploy/`. M0 enthält Proxy und certbot im Staging-Modus.
- ADR-0003, README angepasst

## Auswirkungen

Noch kein Code. Neue Konfigurationswerte sind geplant: `COMPOSE_PROFILES`, `PUBLIC_HOSTNAME`, `SPRI_HOSTNAME`, `APP_HOSTNAME`, `ACME_EMAIL`, `ACME_STAGING`, `SPRI_OUTBOUND_PROXY`.

## Tests

Keine. Für M0 ist ein Compose-Smoke-Test mit Proxy im Staging-Modus vorgesehen.
