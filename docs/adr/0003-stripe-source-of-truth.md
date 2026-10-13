# ADR 0003 · Stripe is the billing source of truth; webhooks are idempotent

**Status:** accepted

## Context

Plans change in Stripe (checkout, upgrades, failed payments, cancellations). Keeping a second copy of billing logic
in the app drifts. Stripe delivers events at least once and may retry or re-deliver the same event.

## Decision

- The app never decides what a tenant has paid for. `customer.subscription.created|updated|deleted` events set the
  tenant's plan from the price's `lookup_key` (`pro_monthly` → `pro`, ...); non-paying statuses drop to `free`.
- `POST /webhooks/stripe` verifies `Stripe-Signature` (HMAC-SHA256 over `t.payload`, constant-time compare, 5-minute
  tolerance against replays) before parsing anything.
- Each event id is inserted into `catalog.processed_stripe_events` **in the same transaction** as its effect. A
  duplicate hits the primary key, is acknowledged with 200 and does nothing.
- After commit, the tenant's cached config is evicted so the next request sees the new limits.

## Consequences

- A plan change takes effect when the webhook lands, usually within seconds.
- Retries and duplicates are safe; a crash between effect and acknowledgement just means Stripe retries and the
  duplicate is skipped.
- Tested by `StripeWebhookIT` (upgrade, redelivery, cancellation, forged/stale signatures) and
  `StripeSignatureVerifierTest`; `scripts/demo.sh` sends signed events to the running stack.
