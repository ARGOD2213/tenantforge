# ADR 0001 · Tenant isolation is enforced by Postgres Row-Level Security

**Status:** accepted

## Context

Pooled tenants share tables. The usual approach, a `where tenant_id = ?` in every query, fails open: one missed
filter in one repository method leaks another customer's data, and code review is the only safety net.

## Decision

The database enforces isolation.

- Every tenant table has `tenant_id` and **one** policy: rows are visible and writable only when `tenant_id`
  equals the connection's `app.tenant_id` setting (`USING` and `WITH CHECK`).
- `ENABLE` **and** `FORCE ROW LEVEL SECURITY`, so even the table owner is filtered.
- The API and worker connect as `tf_app`: not the owner, `NOBYPASSRLS`, cannot `set row_security = off`.
  Migrations run as `tf_owner`.
- `TenantAwareDataSource` runs `set_config('app.tenant_id', …)` when a connection is checked out for a request and
  resets it when the connection goes back to the pool. With nothing bound, `nullif(…, '')` is null and the policy
  matches no rows: the safe default is "see nothing".
- Repositories deliberately have **no** tenant filter. If RLS were off, tests would fail.

## Consequences

- A forgotten filter cannot leak data; a forged `tenant_id` on insert is rejected by the database.
- Every connection checkout pays one extra round trip (`select set_config(...)`), well under a millisecond.
- Tests prove the guarantee against real Postgres (`TenantIsolationIT`): no-filter queries, invisible ids,
  no tenant bound, forged rows, `row_security = off` refused, connections unbound on return to the pool.
- Cross-tenant work (platform admin reports) needs an explicit, separate role; it can't happen by accident.
