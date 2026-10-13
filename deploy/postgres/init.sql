-- Runs once when the local Postgres volume is created.
-- tf_owner owns schemas and tables and runs migrations. tf_app is what the API and worker connect as:
-- it does not own the tables and has no BYPASSRLS, so row-level security always applies to it.
create role tf_owner login password 'owner';
create role tf_app login password 'app' nosuperuser nobypassrls;

create database tenantforge owner tf_owner;
-- the demo's enterprise tenant (orbit) lives in its own database: same server here, any server in production
create database silo_orbit owner tf_owner;
