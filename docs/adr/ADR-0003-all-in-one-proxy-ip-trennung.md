# ADR-0003: All-in-One-Stack mit nginx und certbot, getrennte Inbound- und Outbound-IPs

Status: angenommen | Datum: 2026-11-01 | Ersetzt: D-15 in der Fassung von ADR-0002

## Kontext

Nach ADR-0002 sollte der Betrieb hinter einem vorhandenen nginx laufen, ein Proxy-Container (Caddy/Traefik) wäre nur optional gewesen. open-spri soll aber für alle Nutzer ein einfaches Startpaket sein. Außerdem stellte sich die Frage, ob die IP für ausgehende Anfragen an Partner und die IP, unter der Partner uns erreichen, getrennt sein dürfen.

Das Security-Dokument des AK (V3.0, Kap. 2.6) verlangt nur, dass Partner die **Outbound-IP-Adressen** des Kunden freischalten. Der eigene Endpunkt wird über die WSDL (URL) ausgetauscht (Spezifikation 3.3). Dass beide Adressen gleich sein müssen, steht nirgends.

## Entscheidung

- **All-in-One als Standard:** Der Stack enthält `proxy` (offizielles nginx-Image) und `certbot` (offizielles Image, Webroot-Verfahren, automatische Verlängerung, ECDSA P-256 fest voreingestellt). Beide stehen im Compose-Profil `proxy`, das standardmäßig aktiv ist.
- **Externer Proxy bleibt möglich:** Dafür wird das Profil abgeschaltet. Die nginx-Konfiguration wird in beiden Varianten identisch aus `deploy/nginx/` gepflegt.
- nginx statt Caddy/Traefik: Ein Betreiber kennt nginx am ehesten, und die Konfiguration ist vollständig sichtbar. Das passt zum Grundsatz „Keine Blackbox“.
- **Inbound- und Outbound-Adressen sind getrennt konfigurierbar und dokumentiert.** Ausgehender Verkehr kann über SNAT des Hosts oder einen optionalen Egress-Proxy mit fester IP laufen. Selbsttests prüfen TLS-Zertifikat und Outbound-IP.

## Konsequenzen

- Ports 80 und 443 müssen am Host frei sein, oder es wird DNS-01 bzw. ein externer Proxy genutzt.
- Partner bekommen ein Onboarding-Blatt mit Inbound-URL/-IP und Outbound-IPs.
- Für die HTTP-01-Challenge braucht der Proxy Port 80. Dieser Port leitet ansonsten nur auf HTTPS um.
