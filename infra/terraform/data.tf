# --- encryption keys -------------------------------------------------------------------------------------------

# Platform key: Aurora storage, Performance Insights, Redis at rest, Secrets Manager.
resource "aws_kms_key" "platform" {
  description             = "${local.name} platform data (Aurora, ElastiCache, Secrets Manager)"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "platform" {
  name          = "alias/${local.name}-platform"
  target_key_id = aws_kms_key.platform.key_id
}

# --- Aurora PostgreSQL: pooled cluster + one cluster per silo tenant --------------------------------------------

resource "aws_security_group" "aurora" {
  name        = "${local.name}-aurora"
  description = "Postgres from EKS nodes and pods only"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "aurora_from_eks" {
  security_group_id            = aws_security_group.aurora.id
  referenced_security_group_id = module.eks.node_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "API, worker and Keycloak pods"
}

resource "aws_rds_cluster_parameter_group" "postgres" {
  name        = "${local.name}-aurora-pg16"
  family      = "aurora-postgresql16"
  description = "TLS only, slow-query logging"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }
}

module "aurora_pooled" {
  source = "./modules/aurora-cluster"

  identifier           = "${local.name}-pooled"
  engine_version       = var.aurora_engine_version
  min_acu              = var.aurora_min_acu
  max_acu              = var.aurora_max_acu
  reader_count         = var.aurora_reader_count
  db_subnet_group_name = module.vpc.database_subnet_group_name
  security_group_ids   = [aws_security_group.aurora.id]
  parameter_group_name = aws_rds_cluster_parameter_group.postgres.name
  kms_key_arn          = aws_kms_key.platform.arn
  tags                 = { tier = "pooled" }
}

# Pool-then-silo: an enterprise tenant that needs its own database gets a dedicated cluster here.
# The application code and schema are identical; only the tenant's catalog row changes.
module "aurora_silo" {
  source   = "./modules/aurora-cluster"
  for_each = var.silo_tenants

  identifier           = "${local.name}-silo-${each.key}"
  engine_version       = var.aurora_engine_version
  min_acu              = each.value.min_acu
  max_acu              = each.value.max_acu
  reader_count         = 0
  db_subnet_group_name = module.vpc.database_subnet_group_name
  security_group_ids   = [aws_security_group.aurora.id]
  parameter_group_name = aws_rds_cluster_parameter_group.postgres.name
  kms_key_arn          = aws_kms_key.platform.arn
  tags                 = { tier = "silo", tenant = each.key }
}

# --- ElastiCache Redis: token buckets and hot tenant config ----------------------------------------------------

resource "aws_security_group" "redis" {
  name        = "${local.name}-redis"
  description = "Redis from EKS nodes and pods only"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_eks" {
  security_group_id            = aws_security_group.redis.id
  referenced_security_group_id = module.eks.node_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  description                  = "API and worker pods"
}

resource "random_password" "redis" {
  length  = 48
  special = false
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id = "${local.name}-redis"
  description          = "Per-tenant rate-limit buckets and hot tenant config"

  engine               = "redis"
  engine_version       = "7.1"
  parameter_group_name = "default.redis7"
  node_type            = var.redis_node_type
  port                 = 6379

  num_cache_clusters         = 1 + var.redis_replicas
  automatic_failover_enabled = var.redis_replicas > 0
  multi_az_enabled           = var.redis_replicas > 0

  subnet_group_name  = module.vpc.elasticache_subnet_group_name
  security_group_ids = [aws_security_group.redis.id]

  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.platform.arn
  transit_encryption_enabled = true
  auth_token                 = random_password.redis.result

  snapshot_retention_limit = 3
  snapshot_window          = "21:00-22:00"
}

# --- SQS: export jobs + dead-letter queue -----------------------------------------------------------------------

resource "aws_sqs_queue" "exports_dlq" {
  name                      = "${local.name}-exports-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "exports" {
  name                       = "${local.name}-exports"
  visibility_timeout_seconds = var.export_visibility_timeout
  receive_wait_time_seconds  = 20
  message_retention_seconds  = 345600
  sqs_managed_sse_enabled    = true

  # the worker marks a job failed after its own retry budget; the redrive policy is the backstop
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.exports_dlq.arn
    maxReceiveCount     = var.export_max_receives
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "exports_dlq" {
  queue_url = aws_sqs_queue.exports_dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.exports.arn]
  })
}

resource "aws_cloudwatch_metric_alarm" "exports_dlq" {
  alarm_name          = "${local.name}-exports-dlq-not-empty"
  alarm_description   = "Export jobs are landing in the dead-letter queue"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.exports_dlq.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]
}

# --- S3: tenant files under tenants/{tenantId}/, encrypted with a dedicated KMS key -----------------------------

resource "aws_kms_key" "tenant_files" {
  description             = "${local.name} tenant files in S3"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "tenant_files" {
  name          = "alias/${local.name}-tenant-files"
  target_key_id = aws_kms_key.tenant_files.key_id
}

resource "aws_s3_bucket" "tenant_files" {
  bucket = "${local.name}-files-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_public_access_block" "tenant_files" {
  bucket                  = aws_s3_bucket.tenant_files.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "tenant_files" {
  bucket = aws_s3_bucket.tenant_files.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "tenant_files" {
  bucket = aws_s3_bucket.tenant_files.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tenant_files" {
  bucket = aws_s3_bucket.tenant_files.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.tenant_files.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "tenant_files" {
  bucket = aws_s3_bucket.tenant_files.id

  rule {
    id     = "tidy"
    status = "Enabled"
    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 30
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

data "aws_iam_policy_document" "tenant_files_bucket" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.tenant_files.arn,
      "${aws_s3_bucket.tenant_files.arn}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  statement {
    sid       = "DenyNonKmsEncryption"
    effect    = "Deny"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.tenant_files.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "StringNotEqualsIfExists"
      variable = "s3:x-amz-server-side-encryption"
      values   = ["aws:kms"]
    }
  }
}

resource "aws_s3_bucket_policy" "tenant_files" {
  bucket = aws_s3_bucket.tenant_files.id
  policy = data.aws_iam_policy_document.tenant_files_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.tenant_files]
}
