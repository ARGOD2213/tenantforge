# ADR 0002 · Pool first, silo on demand, routed by a tenant catalog

**Status:** accepted

## Context

Most tenants are small and should share cheap infrastructure. A few enterprise tenants need their own database
(noisy-neighbour protection, data-residency or contractual isolation, independent restore). Forking the code or the
schema per tier would double the maintenance.

## Decision

- A **tenant catalog** (`catalog.tenants`, pooled database) records each tenant's plan and tier: `POOLED`, or
  `SILO` with `db_host` / `db_port` / `db_name`.
- `TenantRoutingDataSource` picks the pooled pool or the tenant's silo pool per connection. Silo pools are created
  lazily (`SiloDataSources`), one small Hikari pool per silo tenant.
- One schema for both tiers: `db/tenant` migrations, **including the RLS policies**, run on the pooled database and
  every silo (`TenantMigrations`). A new silo is migrated the first time it is used.
- Catalog reads are cached in-process and in Redis (`tf:{tenantId}:config`), evicted when a Stripe webhook changes
  the plan. The catalog row in Postgres stays the source of truth.
- Terraform creates a dedicated Aurora cluster per entry in `silo_tenants`; moving a tenant is a data copy plus a
  catalog update.

## Consequences

- Small tenants cost almost nothing; a big tenant can be moved without code changes.
- Migrations must stay backwards compatible across databases that may be one version apart for a moment
  (see [migrations.md](../migrations.md)).
- RLS still applies inside a silo, so a misrouted connection can't expose rows either.
- Tested by `TenantIsolationIT.siloTenantRowsLiveInTheirOwnDatabase` and `MigrationsIT`.
