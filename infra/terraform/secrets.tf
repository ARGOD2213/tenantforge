# One Secrets Manager secret per environment, synced into the cluster by the External Secrets Operator
# (k8s/base/config.yaml -> ExternalSecret key "tenantforge/prod"). Nothing secret is committed to git.

resource "random_password" "db_app" {
  length  = 32
  special = false
}

resource "random_password" "db_owner" {
  length  = 32
  special = false
}

resource "random_password" "keycloak_db" {
  length  = 32
  special = false
}

resource "random_password" "keycloak_admin" {
  length  = 32
  special = false
}

resource "random_password" "keycloak_client" {
  length  = 40
  special = false
}

resource "random_password" "nextauth" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "app" {
  name                    = "tenantforge/${var.environment}"
  description             = "TenantForge runtime secrets"
  kms_key_id              = aws_kms_key.platform.arn
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id

  secret_string = jsonencode({
    DB_APP_PASSWORD             = random_password.db_app.result
    DB_OWNER_PASSWORD           = random_password.db_owner.result
    REDIS_PASSWORD              = random_password.redis.result
    NEXTAUTH_SECRET             = random_password.nextauth.result
    KEYCLOAK_CLIENT_SECRET      = random_password.keycloak_client.result
    KC_DB_PASSWORD              = random_password.keycloak_db.result
    KC_BOOTSTRAP_ADMIN_PASSWORD = random_password.keycloak_admin.result
    # set from the Stripe dashboard after the first apply (aws secretsmanager put-secret-value ...)
    STRIPE_WEBHOOK_SECRET = "set-me"
  })

  # the Stripe signing secret is set by hand; don't overwrite it on the next apply
  lifecycle {
    ignore_changes = [secret_string]
  }
}
