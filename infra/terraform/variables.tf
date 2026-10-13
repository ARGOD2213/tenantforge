variable "region" {
  description = "AWS region for every resource."
  type        = string
  default     = "ap-south-1"
}

variable "environment" {
  description = "Environment name, used in tags and resource names."
  type        = string
  default     = "prod"
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC. Split into public, private (EKS) and database subnets across three AZs."
  type        = string
  default     = "10.40.0.0/16"
}

variable "single_nat_gateway" {
  description = "One NAT gateway instead of one per AZ. Cheaper, but egress depends on a single AZ."
  type        = bool
  default     = false
}

variable "cluster_version" {
  description = "EKS Kubernetes version."
  type        = string
  default     = "1.31"
}

variable "node_instance_types" {
  description = "Instance types for the general managed node group (API, web, workers)."
  type        = list(string)
  default     = ["m7i.large", "m6i.large"]
}

variable "node_capacity_type" {
  description = "ON_DEMAND or SPOT for the general node group."
  type        = string
  default     = "ON_DEMAND"

  validation {
    condition     = contains(["ON_DEMAND", "SPOT"], var.node_capacity_type)
    error_message = "node_capacity_type must be ON_DEMAND or SPOT."
  }
}

variable "node_min_size" {
  type    = number
  default = 3
}

variable "node_max_size" {
  type    = number
  default = 12
}

variable "cluster_admin_arns" {
  description = "IAM principals (users or roles) that get cluster-admin through EKS access entries."
  type        = list(string)
  default     = []
}

variable "aurora_engine_version" {
  description = "Aurora PostgreSQL engine version (RLS and FORCE ROW LEVEL SECURITY need 9.5+; the app targets 16)."
  type        = string
  default     = "16.4"
}

variable "aurora_min_acu" {
  description = "Aurora Serverless v2 minimum capacity of the pooled cluster."
  type        = number
  default     = 0.5
}

variable "aurora_max_acu" {
  description = "Aurora Serverless v2 maximum capacity of the pooled cluster."
  type        = number
  default     = 16
}

variable "aurora_reader_count" {
  description = "Reader instances in the pooled cluster (failover targets and read capacity)."
  type        = number
  default     = 1
}

variable "silo_tenants" {
  description = <<-EOT
    Enterprise tenants that get a dedicated Aurora cluster (the "silo" half of pool-then-silo).
    Key: tenant slug. After apply, point the tenant's catalog row at the silo_endpoints output
    (db_host, db_port, db_name = "tenantforge") and the app migrates and routes to it.
  EOT
  type = map(object({
    min_acu = optional(number, 0.5)
    max_acu = optional(number, 8)
  }))
  default = {}
}

variable "redis_node_type" {
  description = "ElastiCache node type for rate-limit buckets and hot tenant config."
  type        = string
  default     = "cache.t4g.medium"
}

variable "redis_replicas" {
  description = "Replicas per Redis shard (Multi-AZ failover needs at least 1)."
  type        = number
  default     = 1
}

variable "export_visibility_timeout" {
  description = "SQS visibility timeout for export jobs, in seconds. Longer than the slowest export."
  type        = number
  default     = 300
}

variable "export_max_receives" {
  description = "Deliveries before an export message moves to the dead-letter queue."
  type        = number
  default     = 5
}

variable "alarm_topic_arn" {
  description = "SNS topic for alarms (dead-letter queue not empty). Empty: alarms are created without actions."
  type        = string
  default     = ""
}

variable "waf_rate_limit_per_ip" {
  description = "WAF rate-based rule: requests per IP in a 5-minute window before blocking. Coarse edge guard; per-tenant limits live in the app."
  type        = number
  default     = 2000
}

variable "enable_cloudfront" {
  description = "Put CloudFront in front of the web app's ALB for static asset caching."
  type        = bool
  default     = false
}

variable "web_origin_domain" {
  description = "Domain of the ALB (or its DNS alias) that serves the web app. Needed only when enable_cloudfront = true."
  type        = string
  default     = ""
}

variable "web_aliases" {
  description = "Custom domain names for the CloudFront distribution (needs a us-east-1 ACM certificate)."
  type        = list(string)
  default     = []
}

variable "cloudfront_certificate_arn" {
  description = "us-east-1 ACM certificate for web_aliases. Leave empty to use the default CloudFront certificate."
  type        = string
  default     = ""
}

variable "k8s_namespace" {
  description = "Namespace the app runs in (must match k8s/base)."
  type        = string
  default     = "tenantforge"
}
