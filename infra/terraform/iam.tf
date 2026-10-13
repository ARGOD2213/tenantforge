# IRSA: each Kubernetes service account assumes its own IAM role through the cluster's OIDC provider.
# The API and worker get only what their code calls, and S3 access is limited to the tenants/ prefix.

locals {
  tenant_objects = "${aws_s3_bucket.tenant_files.arn}/tenants/*"

  irsa = {
    api = {
      role_name       = "${local.name}-api"
      namespace       = var.k8s_namespace
      service_account = "tenantforge-api"
      policy          = data.aws_iam_policy_document.api.json
    }
    worker = {
      role_name       = "${local.name}-worker"
      namespace       = var.k8s_namespace
      service_account = "tenantforge-worker"
      policy          = data.aws_iam_policy_document.worker.json
    }
    keda = {
      role_name       = "${local.name}-keda-operator"
      namespace       = "keda"
      service_account = "keda-operator"
      policy          = data.aws_iam_policy_document.keda.json
    }
    external_secrets = {
      role_name       = "${local.name}-external-secrets"
      namespace       = "external-secrets"
      service_account = "external-secrets"
      policy          = data.aws_iam_policy_document.external_secrets.json
    }
  }
}

# API: enqueue exports, presign downloads of tenant files.
data "aws_iam_policy_document" "api" {
  statement {
    sid       = "EnqueueExports"
    actions   = ["sqs:SendMessage", "sqs:GetQueueAttributes"]
    resources = [aws_sqs_queue.exports.arn]
  }

  statement {
    sid       = "ReadTenantFiles"
    actions   = ["s3:GetObject"]
    resources = [local.tenant_objects]
  }

  statement {
    sid       = "DecryptTenantFiles"
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.tenant_files.arn]
  }
}

# Worker: consume the export queue, write tenant files.
data "aws_iam_policy_document" "worker" {
  statement {
    sid = "ConsumeExports"
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:GetQueueAttributes",
    ]
    resources = [aws_sqs_queue.exports.arn]
  }

  statement {
    sid       = "WriteTenantFiles"
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = [local.tenant_objects]
  }

  statement {
    sid       = "EncryptTenantFiles"
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [aws_kms_key.tenant_files.arn]
  }
}

# KEDA operator: read queue depth to scale workers from 0.
data "aws_iam_policy_document" "keda" {
  statement {
    actions   = ["sqs:GetQueueAttributes"]
    resources = [aws_sqs_queue.exports.arn]
  }
}

# External Secrets Operator: sync the app secret into the namespace.
data "aws_iam_policy_document" "external_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = [aws_secretsmanager_secret.app.arn]
  }

  statement {
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.platform.arn]
  }
}

data "aws_iam_policy_document" "irsa_trust" {
  for_each = local.irsa

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [module.eks.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${module.eks.oidc_provider}:sub"
      values   = ["system:serviceaccount:${each.value.namespace}:${each.value.service_account}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${module.eks.oidc_provider}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "irsa" {
  for_each = local.irsa

  name               = each.value.role_name
  assume_role_policy = data.aws_iam_policy_document.irsa_trust[each.key].json
}

resource "aws_iam_role_policy" "irsa" {
  for_each = local.irsa

  name   = "access"
  role   = aws_iam_role.irsa[each.key].id
  policy = each.value.policy
}

# AWS Load Balancer Controller (creates the ALB for the Ingress and attaches the WAF ACL).
module "lb_controller_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.48"

  role_name                              = "${local.name}-aws-load-balancer-controller"
  attach_load_balancer_controller_policy = true

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:aws-load-balancer-controller"]
    }
  }
}
