#!/bin/sh
# Source NAT for the open-spri bridge (deploy/egress/compose.snat.yaml): every connection that leaves the
# host from the stack carries OUTBOUND_IP as source address, the address partners allowlist (AK security
# document 2.6). OUTBOUND_IP must be configured on an interface of the host. Idempotent; run after each boot
# (e.g. as systemd unit or from /etc/rc.local), or persist with iptables-save / nftables.
set -eu
: "${OUTBOUND_IP:?set OUTBOUND_IP, e.g. OUTBOUND_IP=203.0.113.10}"
SUBNET="${SUBNET:-172.30.81.0/24}"
BRIDGE="${BRIDGE:-br-openspri}"

rule="POSTROUTING -s $SUBNET ! -o $BRIDGE -j SNAT --to-source $OUTBOUND_IP"
# shellcheck disable=SC2086
if ! iptables -t nat -C $rule 2>/dev/null; then
  iptables -t nat -I $rule
fi
echo "SNAT: $SUBNET -> $OUTBOUND_IP"
echo "Check: Einstellungen > Selbsttests > Outbound-IP (needs OPENSPRI_SELFTEST_IP_ECHO_URL)"
