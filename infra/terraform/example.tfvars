# cp example.tfvars prod.tfvars && terraform plan -var-file=prod.tfvars
region      = "ap-south-1"
environment = "prod"

# who can run kubectl as cluster-admin (the identity running terraform is added automatically)
cluster_admin_arns = [
  # "arn:aws:iam::123456789012:role/platform-admins",
]

# a smaller footprint for a first launch; raise as tenants arrive
single_nat_gateway = true
node_min_size      = 2
node_max_size      = 8
aurora_min_acu     = 0.5
aurora_max_acu     = 8

# enterprise tenants on a dedicated database (pool-then-silo). Empty until the first one signs.
silo_tenants = {
  # orbit = { min_acu = 0.5, max_acu = 4 }
}

# alarm_topic_arn = "arn:aws:sns:ap-south-1:123456789012:oncall"

# enable after the first deploy, once the ALB exists
enable_cloudfront = false
# web_origin_domain          = "app-origin.example.com"
# web_aliases                = ["app.example.com"]
# cloudfront_certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/..."
