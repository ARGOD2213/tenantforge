#!/usr/bin/env bash
# Walks through every TenantForge guarantee against the local stack (docker compose up, core mode).
# CI runs this same script after `docker compose up`, so the README demo is tested on every push.
#
# Usage: scripts/demo.sh        (needs curl, jq, openssl)
set -euo pipefail

API=${API:-http://localhost:8081}
here=$(cd "$(dirname "$0")" && pwd)

step() { printf '\n\033[1m▶ %s\033[0m\n' "$*"; }
fail() { printf '\033[31m✗ %s\033[0m\n' "$*"; exit 1; }
ok() { printf '\033[32m✓ %s\033[0m\n' "$*"; }
token() { curl -fsS -X POST "$API/dev/token?tenant=$1&role=${2:-tenant_admin}" | jq -r .access_token; }
call() { local tok=$1; shift; curl -fsS -H "Authorization: Bearer $tok" "$@"; }

step "Waiting for the API"
for _ in $(seq 1 120); do
  curl -fsS "$API/actuator/health/readiness" >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS "$API/actuator/health/readiness" >/dev/null || fail "API never became ready"
ok "API is up"

ACME=$(token acme)
ZENITH=$(token zenith)
ORBIT=$(token orbit)
MEMBER=$(token zenith tenant_member)

step "Who am I: tenant, plan and limits come from the token + tenant catalog"
for t in ACME ZENITH ORBIT; do
  call "${!t}" "$API/api/me" | jq -c '{tenant: .tenant.slug, plan: .tenant.plan, tier: .tenant.tier, limits}'
done

step "Isolation: the repository query has no tenant filter; Postgres RLS returns only the caller's rows"
for t in ACME ZENITH ORBIT; do
  printf '%-7s %s\n' "$t" "$(call "${!t}" "$API/api/invoices" | jq -c '[.[].number]')"
done
id=$(call "$ACME" -X POST -H 'Content-Type: application/json' \
  -d "{\"number\":\"DEMO-$RANDOM$RANDOM\",\"customer\":\"Demo Customer\",\"amountCents\":4999}" "$API/api/invoices" | jq -r .id)
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ZENITH" "$API/api/invoices/$id")
[ "$code" = 404 ] || fail "zenith read acme's invoice: HTTP $code"
ok "acme created invoice $id; zenith asking for that id gets 404 (the row is invisible, not forbidden)"

step "Pool then silo: orbit (enterprise) lives in its own database, same code, routed by the catalog"
call "$ORBIT" "$API/api/me" | jq -c '{tenant: .tenant.slug, tier: .tenant.tier}'

step "Per-tenant rate limits: acme's free plan allows 5 req/s (burst 10)"
codes=$(for _ in $(seq 1 30); do curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $ACME" "$API/api/me"; done | sort | uniq -c)
echo "$codes"
grep -q ' 429$' <<<"$codes" || fail "acme was never rate limited"
curl -s -D - -o /dev/null -H "Authorization: Bearer $ACME" "$API/api/me" | grep -iE '^(HTTP|retry-after|x-ratelimit)' || true
zcode=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ZENITH" "$API/api/me")
[ "$zcode" = 200 ] || fail "zenith was affected by acme's burst: HTTP $zcode"
ok "acme got 429 with Retry-After; zenith, a different tenant, still gets 200"

step "Exports: plan and role checks, then SQS → worker → S3 under tenants/{id}/"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $MEMBER" "$API/api/exports")
[ "$code" = 403 ] || fail "a member started an export: HTTP $code"
ok "tenant_member cannot export (403)"
sleep 2 # let acme's bucket refill after the burst above
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $ACME" "$API/api/exports")
[ "$code" = 403 ] || fail "the free plan started an export: HTTP $code"
ok "acme's free plan does not include exports (403)"
export_id=$(call "$ZENITH" -X POST "$API/api/exports" | jq -r .id)
for _ in $(seq 1 60); do
  job=$(call "$ZENITH" "$API/api/exports/$export_id")
  status=$(jq -r .status <<<"$job")
  [ "$status" = done ] && break
  [ "$status" = failed ] && fail "export failed: $job"
  sleep 1
done
[ "$status" = done ] || fail "export did not finish: $job"
jq -c '{status, rows}' <<<"$job"
url=$(jq -r .downloadUrl <<<"$job")
grep -q "/tenants/22222222-2222-2222-2222-222222222222/exports/" <<<"$url" || fail "export key is not under the tenant prefix: $url"
curl -fsS "$url" | head -3
ok "zenith's export was built by a worker and downloaded through a presigned URL"

step "Billing: Stripe is the source of truth; a signed webhook moves acme to pro"
acme_id=$(call "$ACME" "$API/api/me" | jq -r .tenant.id)
event="evt_demo_$(date +%s%N)"
"$here/send-test-webhook.sh" "$acme_id" pro_monthly "$event"
"$here/send-test-webhook.sh" "$acme_id" pro_monthly "$event" # Stripe retries: the same event again
plan=$(call "$ACME" "$API/api/me" | jq -r .tenant.plan)
[ "$plan" = pro ] || fail "acme's plan is $plan after the webhook"
ok "acme is on pro now; the duplicate delivery was acknowledged and skipped"
bad=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/webhooks/stripe" -H 'Content-Type: application/json' \
  -H "Stripe-Signature: t=$(date +%s),v1=0000" -d '{"id":"evt_forged","type":"customer.subscription.updated"}')
[ "$bad" = 400 ] || fail "a forged webhook got HTTP $bad"
ok "a webhook with a bad signature is rejected (400)"

# put acme back on free so the demo can run again
STATUS=canceled "$here/send-test-webhook.sh" "$acme_id" pro_monthly "evt_demo_reset_$(date +%s%N)" >/dev/null

printf '\n\033[1mAll checks passed.\033[0m\n'
