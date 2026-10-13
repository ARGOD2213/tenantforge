-- Tenant tables. Applied to the pooled database AND to every silo database, so isolation never depends on
-- which database a tenant happens to live in.
--
-- Every table:
--   * has tenant_id defaulting to the connection's tenant (app.tenant_id, set on checkout by the API)
--   * ENABLE + FORCE row level security, so even the table owner is filtered
--   * one policy: rows are visible and writable only when tenant_id matches the connection's tenant.
--     With no tenant set, nullif(...) is null and nothing matches: the safe default is "no rows".

create table app.invoices (
    id            uuid primary key default gen_random_uuid(),
    tenant_id     uuid not null default nullif(current_setting('app.tenant_id', true), '')::uuid,
    number        text not null,
    customer      text not null,
    amount_cents  bigint not null check (amount_cents >= 0),
    currency      char(3) not null default 'INR',
    status        text not null default 'draft' check (status in ('draft', 'sent', 'paid', 'void')),
    issued_on     date not null default current_date,
    created_at    timestamptz not null default now(),
    unique (tenant_id, number)
);

create index invoices_tenant_created_idx on app.invoices (tenant_id, created_at desc);

alter table app.invoices enable row level security;
alter table app.invoices force row level security;

create policy tenant_isolation on app.invoices
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table app.exports (
    id            uuid primary key default gen_random_uuid(),
    tenant_id     uuid not null default nullif(current_setting('app.tenant_id', true), '')::uuid,
    kind          text not null check (kind in ('invoices_csv')),
    status        text not null default 'queued' check (status in ('queued', 'running', 'done', 'failed')),
    s3_key        text,
    rows_written  integer,
    error         text,
    requested_by  text not null,
    created_at    timestamptz not null default now(),
    completed_at  timestamptz
);

create index exports_tenant_created_idx on app.exports (tenant_id, created_at desc);

alter table app.exports enable row level security;
alter table app.exports force row level security;

create policy tenant_isolation on app.exports
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

grant usage on schema app to ${appRole};
grant select, insert, update, delete on app.invoices, app.exports to ${appRole};
