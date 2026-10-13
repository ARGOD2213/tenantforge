-- Same roles as deploy/postgres/init.sql: an owner for migrations and a runtime role that RLS always applies to.
create role tf_owner login password 'owner';
create role tf_app login password 'app' nosuperuser nobypassrls;
alter database tenantforge owner to tf_owner;
create database silo_orbit owner tf_owner;
