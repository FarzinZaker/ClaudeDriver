variable "environment" {
  description = "Deployment environment name; stamped as the Environment cost-allocation tag on every resource."
  type        = string
  default     = "dev"
}

variable "aws_region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-east-1"
}

variable "db_password" {
  description = "Master password for the RDS PostgreSQL instance. Supply via TF_VAR_db_password or a tfvars file kept out of git; never commit it."
  type        = string
  sensitive   = true
}

variable "db_username" {
  description = "Master username for the RDS PostgreSQL instance."
  type        = string
  default     = "claudedriver"
}

variable "db_name" {
  description = "Initial database name created on the RDS instance."
  type        = string
  default     = "claudedriver"
}

variable "acm_certificate_arn" {
  description = "ARN of the ACM certificate for the operator/web + agent HTTPS listeners. Operator must create/validate this cert for var.domain_name before apply."
  type        = string
}

variable "domain_name" {
  description = "Public DNS name the backend is served on (must match the ACM certificate). Used for documentation/outputs and SSM config."
  type        = string
}

variable "agent_ca_bundle_s3_bucket" {
  description = "S3 bucket holding the device-CA trust bundle (PEM) used for agent mTLS client-certificate verification. Operator uploads the CA bundle here before apply."
  type        = string
}

variable "agent_ca_bundle_s3_key" {
  description = "S3 object key of the device-CA trust bundle (PEM) within var.agent_ca_bundle_s3_bucket."
  type        = string
  default     = "claudedriver/agent-ca-bundle.pem"
}

variable "budget_notification_email" {
  description = "Email address that receives the monthly AWS Budget threshold alert (Principle VII)."
  type        = string
}

variable "monthly_budget_limit_usd" {
  description = "Monthly cost ceiling (USD) for the ClaudeDriver AWS Budget."
  type        = string
  default     = "50"
}

variable "agent_runtimes_bucket" {
  description = "S3 bucket holding the self-contained per-OS agent runtimes the backend wraps into per-machine installers. Empty disables the installer endpoint."
  type        = string
  default     = ""
}

variable "enable_budget" {
  description = "Whether to create the AWS Budget. Must be false when deploying into a linked/member account of an Organization (only the payer account can create budgets); create the tag-scoped budget in the payer account instead."
  type        = bool
  default     = true
}

variable "container_image" {
  description = "Fully-qualified container image reference for the backend ECS task (e.g. <account>.dkr.ecr.<region>.amazonaws.com/claudedriver-backend:<tag>)."
  type        = string
}

variable "backend_container_port" {
  description = "Port the Ktor backend listens on inside the container."
  type        = number
  default     = 8080
}

variable "session_signing_key" {
  description = "Secret key used by the backend to sign operator session tokens. Supply via TF_VAR_session_signing_key or a tfvars file kept out of git; never commit it."
  type        = string
  sensitive   = true
}

variable "operator_bootstrap_code" {
  description = "One-time bootstrap code the backend uses to enroll the first operator. Supply via TF_VAR_operator_bootstrap_code or a tfvars file kept out of git; never commit it."
  type        = string
  sensitive   = true
}

variable "ca_cert_pem" {
  description = "Persistent device-CA certificate (PEM) the backend uses to issue/verify device client certificates. Supply via TF_VAR_ca_cert_pem or a tfvars file kept out of git; never commit it."
  type        = string
  sensitive   = true
}

variable "ca_key_pem" {
  description = "Persistent device-CA private key (PEM) the backend uses to sign device client certificates. Supply via TF_VAR_ca_key_pem or a tfvars file kept out of git; never commit it."
  type        = string
  sensitive   = true
}
