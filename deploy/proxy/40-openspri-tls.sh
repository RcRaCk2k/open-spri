#!/bin/sh
# Selects the TLS certificate for nginx and keeps it current:
# - Let's Encrypt certificate from /etc/letsencrypt/live/$PUBLIC_HOSTNAME if present,
# - otherwise a temporary self-signed certificate so that nginx can start and answer the ACME challenge.
# A background loop switches to the real certificate and reloads nginx after every renewal: every
# CERT_WAIT_INTERVAL (10 s) while the temporary certificate is in use, so that the certificate from certbot is
# active a few seconds after it was issued, afterwards every CERT_CHECK_INTERVAL (5 min) for renewals.
set -eu

LIVE="/etc/letsencrypt/live/${PUBLIC_HOSTNAME}"
BOOT="/etc/nginx/tls-bootstrap"
TLS="/etc/nginx/tls"
INTERVAL="${CERT_CHECK_INTERVAL:-300}"
WAIT_INTERVAL="${CERT_WAIT_INTERVAL:-10}"
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
    while :; do
        case "$(readlink "$TLS/fullchain.pem")" in
            "$BOOT"/*) sleep "$WAIT_INTERVAL" ;;
            *) sleep "$INTERVAL" ;;
        esac
        select_cert
        next="$(fingerprint)"
        if [ "$next" != "$current" ]; then
            echo "open-spri proxy: certificate changed, reloading nginx"
            nginx -s reload && current="$next"
        fi
    done
) &
