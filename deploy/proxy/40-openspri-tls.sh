#!/bin/sh
# Selects the TLS certificate for nginx and keeps it current:
# - Let's Encrypt certificate from /etc/letsencrypt/live/$PUBLIC_HOSTNAME if present,
# - otherwise a temporary self-signed certificate so that nginx can start and answer the ACME challenge.
# A background loop switches to the real certificate and reloads nginx after every renewal.
set -eu

LIVE="/etc/letsencrypt/live/${PUBLIC_HOSTNAME}"
BOOT="/etc/nginx/tls-bootstrap"
TLS="/etc/nginx/tls"
INTERVAL="${CERT_CHECK_INTERVAL:-300}"
mkdir -p "$TLS" "$BOOT"

if [ ! -s "$BOOT/fullchain.pem" ]; then
    openssl req -x509 -nodes -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -days 30 \
        -subj "/CN=${PUBLIC_HOSTNAME}" -keyout "$BOOT/privkey.pem" -out "$BOOT/fullchain.pem" 2>/dev/null
    echo "open-spri proxy: created temporary self-signed certificate (not valid for partner traffic)"
fi

select_cert() {
    if [ -s "$LIVE/fullchain.pem" ] && [ -s "$LIVE/privkey.pem" ]; then src="$LIVE"; else src="$BOOT"; fi
    ln -sfn "$src/fullchain.pem" "$TLS/fullchain.pem"
    ln -sfn "$src/privkey.pem" "$TLS/privkey.pem"
}

fingerprint() { md5sum "$TLS/fullchain.pem" | cut -d' ' -f1; }

select_cert
echo "open-spri proxy: using certificate $(readlink "$TLS/fullchain.pem")"

(
    current="$(fingerprint)"
    while sleep "$INTERVAL"; do
        select_cert
        next="$(fingerprint)"
        if [ "$next" != "$current" ]; then
            echo "open-spri proxy: certificate changed, reloading nginx"
            nginx -s reload && current="$next"
        fi
    done
) &
