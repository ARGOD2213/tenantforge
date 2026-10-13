# Schema changes without downtime: expand, migrate, contract

Rolling deploys mean the old and new release run side by side for a few minutes, and with pool-then-silo the pooled
database and the silos are migrated one after another. Every migration therefore has to work with **both** the
previous and the next version of the code.

## Rules

1. **Expand** first: add nullable columns, new tables, new indexes. Never rename or drop in the same release.
2. Indexes on existing tables are built `CONCURRENTLY`, in a migration file of their own (Flyway runs it outside a
   transaction). `TenantMigrations` sets `flyway.postgresql.transactional.lock=false` so Flyway's own lock does not
   hold a transaction open, which would make the concurrent build wait forever.
3. The new release writes the new shape (and reads both if needed).
4. **Backfill** in batches from a job, not inside a migration that locks the table.
5. **Contract** in a later release, once no old pods run: constraints, `NOT NULL`, dropping old columns.

## Worked example: invoice due dates (in this repo)

| Release | Change | Where |
|---|---|---|
| N | `alter table app.invoices add column due_on date` (nullable, no default: catalog-only, no rewrite) | `db/tenant/V2__invoices_due_on_expand.sql` |
| N | `create index concurrently invoices_tenant_due_idx on app.invoices (tenant_id, due_on)` | `db/tenant/V3__invoices_due_on_index.sql` |
| N | API writes `due_on` (default `issued_on + 30 days`), dashboard shows it, CSV exports include it | `Invoice`, `InvoiceController`, `ExportProcessor` |
| N+1 | Backfill old rows in batches of 5,000 | below |
| N+2 | Contract: enforce `NOT NULL` without a long lock | below |

`MigrationsIT` checks that the pooled database and a silo end at the same version, that the concurrent index is
valid on both, and that a writer from release N-1 (which never sends `due_on`) still works after the expand step.

### Backfill (N+1), per database, as `tf_owner`

```sql
-- repeat until it updates 0 rows
update app.invoices set due_on = issued_on + 30
where id in (select id from app.invoices where due_on is null limit 5000);
```

### Contract (N+2), as `db/tenant/V4__invoices_due_on_contract.sql`

```sql
-- NOT VALID: takes a brief lock, does not scan the table
alter table app.invoices add constraint invoices_due_on_not_null check (due_on is not null) not valid;
-- VALIDATE scans without blocking writes
alter table app.invoices validate constraint invoices_due_on_not_null;
-- Postgres 12+ uses the validated constraint to skip the scan for SET NOT NULL
alter table app.invoices alter column due_on set not null;
alter table app.invoices drop constraint invoices_due_on_not_null;
```
