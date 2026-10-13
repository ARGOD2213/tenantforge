-- Built CONCURRENTLY so writes to app.invoices keep flowing while the index builds on a large table.
-- Flyway runs this file outside a transaction (CREATE INDEX CONCURRENTLY cannot run inside one), so it holds no
-- other statement. If a build is interrupted it leaves an INVALID index: drop it with DROP INDEX CONCURRENTLY,
-- then repair and re-run the migration.

create index concurrently invoices_tenant_due_idx on app.invoices (tenant_id, due_on);
