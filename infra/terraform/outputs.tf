output "region" {
  value = var.region
}

output "cluster_name" {
  value = module.eks.cluster_name
}

output "configure_kubectl" {
  description = "Point kubectl at the new cluster."
  value       = "aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name}"
}

output "aurora_endpoint" {
  description = "Writer endpoint of the pooled cluster."
  value       = module.aurora_pooled.endpoint
}

output "aurora_reader_endpoint" {
  value = module.aurora_pooled.reader_endpoint
}

output "aurora_master_secret_arn" {
  description = "Master credentials (used once by infra/scripts/bootstrap-db.sh, never by the app)."
  value       = module.aurora_pooled.master_secret_arn
}

output "silo_endpoints" {
  description = "Per silo tenant: values for its catalog row (db_host, db_port, db_name) and its master secret."
  value = {
    for slug, cluster in module.aurora_silo : slug => {
      db_host           = cluster.endpoint
      db_port           = cluster.port
      db_name           = "tenantforge"
      master_secret_arn = cluster.master_secret_arn
    }
  }
}

output "redis_endpoint" {
  value = aws_elasticache_replication_group.redis.primary_endpoint_address
}

output "export_queue_url" {
  value = aws_sqs_queue.exports.url
}

output "export_dlq_url" {
  value = aws_sqs_queue.exports_dlq.url
}

output "tenant_bucket" {
  value = aws_s3_bucket.tenant_files.bucket
}

output "tenant_files_kms_key_arn" {
  value = aws_kms_key.tenant_files.arn
}

output "waf_acl_arn" {
  description = "For the alb.ingress.kubernetes.io/wafv2-acl-arn annotation."
  value       = aws_wafv2_web_acl.main.arn
}

output "app_secret_name" {
  value = aws_secretsmanager_secret.app.name
}

output "irsa_role_arns" {
  description = "For the eks.amazonaws.com/role-arn annotations and the KEDA / External Secrets / LB controller Helm values."
  value = merge(
    { for k, role in aws_iam_role.irsa : k => role.arn },
    { aws_load_balancer_controller = module.lb_controller_irsa.iam_role_arn },
  )
}

output "app_config" {
  description = "Values for the tenantforge-config ConfigMap in k8s/overlays/prod."
  value = {
    AWS_REGION       = var.region
    DB_URL           = "jdbc:postgresql://${module.aurora_pooled.endpoint}:5432/tenantforge?sslmode=require"
    REDIS_HOST       = aws_elasticache_replication_group.redis.primary_endpoint_address
    REDIS_SSL        = "true"
    EXPORT_QUEUE_URL = aws_sqs_queue.exports.url
    TENANT_BUCKET    = aws_s3_bucket.tenant_files.bucket
    S3_SSE           = "aws:kms"
    KMS_KEY_ID       = aws_kms_key.tenant_files.arn
  }
}

output "cloudfront_domain" {
  value = var.enable_cloudfront ? aws_cloudfront_distribution.web[0].domain_name : null
}
