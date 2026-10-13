-- The tenant catalog lives in the pooled database only. It is shared platform data, not tenant data,
-- so it has no row-level security; the runtime role can read it and update plans from Stripe.

create table catalog.plans (
    code             text primary key,
    rate_per_second  integer not null check (rate_per_second > 0),
    burst            integer not null check (burst > 0),
    exports_enabled  boolean not null default false
);

insert into catalog.plans (code, rate_per_second, burst, exports_enabled) values
    ('free',        5,  10,  false),
    ('pro',        50, 100,  true),
    ('enterprise', 200, 400, true);

create table catalog.tenants (
    id                  uuid primary key,
    slug                text not null unique,
    name                text not null,
    plan                text not null default 'free' references catalog.plans (code),
    -- POOLED: shares the pooled database, isolated by RLS. SILO: has its own database (same code, same schema).
    tier                text not null default 'POOLED' check (tier in ('POOLED', 'SILO')),
    db_host             text,
    db_port             integer,
    db_name             text,
    stripe_customer_id  text unique,
    status              text not null default 'active' check (status in ('active', 'suspended')),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    constraint silo_has_database check (tier = 'POOLED' or db_name is not null)
);

-- Stripe retries and occasionally re-delivers events. Every processed event id is stored in the same
-- transaction as its effect, so a duplicate is acknowledged and skipped.
create table catalog.processed_stripe_events (
    event_id     text primary key,
    type         text not null,
    received_at  timestamptz not null default now()
);

grant usage on schema catalog to ${appRole};
grant select on catalog.plans to ${appRole};
grant select, insert, update on catalog.tenants to ${appRole};
grant select, insert on catalog.processed_stripe_events to ${appRole};
