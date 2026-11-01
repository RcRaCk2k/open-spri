#!/bin/sh
# Container "certbot": obtains and renews the Let's Encrypt certificate for PUBLIC_HOSTNAME
# via HTTP-01 (webroot shared with the proxy). Key type is fixed to values allowed by the
# AK S&P security document: ECDSA P-256 (default) or RSA 3072.
set -eu

idle() { echo "certbot: $1"; trap 'exit 0' TERM INT; while :; do sleep 3600 & wait $!; done; }

[ "${ACME_ENABLED:-true}" = "true" ] || idle "ACME_ENABLED=false - the proxy keeps the temporary certificate."
case "${PUBLIC_HOSTNAME:-localhost}" in
    localhost|*.localhost|*.local|127.*|*.internal) idle "PUBLIC_HOSTNAME=${PUBLIC_HOSTNAME:-localhost} is not public - ACME skipped." ;;
esac

case "${ACME_KEY_TYPE:-ecdsa}" in
    ecdsa) key="--key-type ecdsa --elliptic-curve secp256r1" ;;
    rsa)   key="--key-type rsa --rsa-key-size 3072" ;;
    *)     echo "certbot: ACME_KEY_TYPE must be 'ecdsa' or 'rsa'"; exit 1 ;;
esac

staging=""
[ "${ACME_STAGING:-false}" = "true" ] && staging="--staging"

email="--register-unsafely-without-email"
[ -n "${ACME_EMAIL:-}" ] && email="--email ${ACME_EMAIL}"

renewal="/etc/letsencrypt/renewal/${PUBLIC_HOSTNAME}.conf"
force=""
# Switching from staging to production (or back) requires a new certificate.
if [ -f "$renewal" ]; then
    if [ -z "$staging" ] && grep -q "acme-staging" "$renewal"; then force="--force-renewal"; fi
    if [ -n "$staging" ] && ! grep -q "acme-staging" "$renewal"; then force="--force-renewal"; fi
fi

if [ ! -f "$renewal" ] || [ -n "$force" ]; then
    # shellcheck disable=SC2086
    until certbot certonly --webroot -w /var/www/certbot -d "$PUBLIC_HOSTNAME" --cert-name "$PUBLIC_HOSTNAME" \
        --agree-tos --no-eff-email --non-interactive $email $staging $key $force; do
        echo "certbot: issuing failed (DNS, port 80 reachable?) - retrying in 5 minutes"
        sleep 300
    done
fi

trap 'exit 0' TERM INT
while :; do
    certbot renew --webroot -w /var/www/certbot --quiet || echo "certbot: renewal failed, retrying in 12 hours"
    sleep 12h & wait $!
done
