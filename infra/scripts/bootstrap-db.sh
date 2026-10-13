#!/usr/bin/env bash
# One-off after the first `terraform apply` (and after adding a silo tenant): creates the database roles and
# databases on Aurora. Runs psql in a short-lived pod inside the cluster, because Aurora has no public endpoint.
#
#   tf_owner  owns the schemas and runs the Flyway migrations (the app connects as it only to migrate)
#   tf_app    what the API and worker use at runtime: not an owner, NOBYPASSRLS, so RLS always applies
#   keycloak  Keycloak's own database (pooled cluster only)
#
# Usage: infra/scripts/bootstrap-db.sh            # pooled cluster
#        infra/scripts/bootstrap-db.sh orbit      # the silo cluster of tenant "orbit"
# Needs: terraform state access, aws cli, jq, kubectl pointed at the cluster.
set -euo pipefail

silo="${1:-}"
tf() { terraform -chdir="$(dirname "$0")/../terraform" output -json "$@"; }

region=$(tf region | jq -r .)
app_secret=$(tf app_secret_name | jq -r .)
if [ -z "$silo" ]; then
  host=$(tf aurora_endpoint | jq -r .)
  master_secret=$(tf aurora_master_secret_arn | jq -r .)
else
  host=$(tf silo_endpoints | jq -r --arg s "$silo" '.[$s].db_host')
  master_secret=$(tf silo_endpoints | jq -r --arg s "$silo" '.[$s].master_secret_arn')
fi

secret() { aws secretsmanager get-secret-value --region "$region" --secret-id "$1" --query SecretString --output text; }
master=$(secret "$master_secret")
app=$(secret "$app_secret")

sql=$(cat <<SQL
\set ON_ERROR_STOP on
select 'create role tf_owner login' where not exists (select from pg_roles where rolname = 'tf_owner') \gexec
select 'create role tf_app login nosuperuser nobypassrls' where not exists (select from pg_roles where rolname = 'tf_app') \gexec
alter role tf_owner password '$(jq -r .DB_OWNER_PASSWORD <<<"$app")';
alter role tf_app password '$(jq -r .DB_APP_PASSWORD <<<"$app")';
grant tf_owner to current_user;
select 'create database tenantforge owner tf_owner' where not exists (select from pg_database where datname = 'tenantforge') \gexec
SQL
)

if [ -z "$silo" ]; then
  sql+=$(cat <<SQL

select 'create role keycloak login' where not exists (select from pg_roles where rolname = 'keycloak') \gexec
alter role keycloak password '$(jq -r .KC_DB_PASSWORD <<<"$app")';
grant keycloak to current_user;
select 'create database keycloak owner keycloak' where not exists (select from pg_database where datname = 'keycloak') \gexec
SQL
)
fi

# the SQL (with passwords) goes over stdin, never into the pod spec; only the master login is an env var
pod="tf-db-bootstrap-$(date +%s)"
kubectl run "$pod" -n tenantforge --rm -i --restart=Never --quiet \
  --image=postgres:16-alpine \
  --env="PGPASSWORD=$(jq -r .password <<<"$master")" \
  -- psql "host=$host port=5432 dbname=postgres user=$(jq -r .username <<<"$master") sslmode=require" <<<"$sql"

echo "Bootstrapped ${silo:-pooled} cluster at $host. The app runs migrations on startup."
