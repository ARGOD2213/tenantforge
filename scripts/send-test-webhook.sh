#!/usr/bin/env bash
# Sends a signed Stripe-style webhook to the API, in Stripe's own format (Stripe-Signature: t=<unix>,v1=<hmac>).
# For real Stripe test events use the Stripe CLI instead: stripe listen --forward-to localhost:8081/webhooks/stripe
#
# Usage: scripts/send-test-webhook.sh <tenant-uuid> [price lookup key: pro_monthly|enterprise_monthly] [event id]
#   STATUS                 subscription status, default active (canceled/unpaid move the tenant back to free)
#   API                    default http://localhost:8081
#   STRIPE_WEBHOOK_SECRET  default whsec_local_dev_secret (what deploy/docker-compose.yml gives the API)
set -euo pipefail

API=${API:-http://localhost:8081}
SECRET=${STRIPE_WEBHOOK_SECRET:-whsec_local_dev_secret}
tenant_id=${1:?tenant uuid}
lookup_key=${2:-pro_monthly}
event_id=${3:-evt_local_$(date +%s%N)}

payload=$(jq -cn --arg id "$event_id" --arg tenant "$tenant_id" --arg key "$lookup_key" --arg status "${STATUS:-active}" '{
  id: $id,
  type: "customer.subscription.updated",
  data: { object: {
    id: "sub_local",
    customer: ("cus_local_" + $tenant[0:8]),
    status: $status,
    metadata: { tenant_id: $tenant },
    items: { data: [ { price: { lookup_key: $key } } ] }
  } }
}')

ts=$(date +%s)
sig=$(printf '%s.%s' "$ts" "$payload" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* //')

curl -fsS -X POST "$API/webhooks/stripe" \
  -H "Content-Type: application/json" \
  -H "Stripe-Signature: t=$ts,v1=$sig" \
  --data-binary "$payload"
echo
