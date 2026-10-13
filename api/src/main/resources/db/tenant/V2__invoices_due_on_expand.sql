-- Expand step of an expand/contract change: invoices get a due date.
--
-- A nullable column with no default is a catalog-only change in Postgres: no table rewrite, and the ACCESS EXCLUSIVE
-- lock is held for milliseconds. The previous release keeps working (it never writes due_on); this release writes it.
-- The contract step (backfill in batches, then NOT NULL) ships in a later release, once no old pods are running.
-- See docs/migrations.md.

alter table app.invoices add column due_on date;
