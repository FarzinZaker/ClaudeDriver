provider "aws" {
  region = var.aws_region

  # FR-021/FR-022 + Constitution Principle VII: every resource this provider
  # creates is stamped with these cost-allocation tags so total ClaudeDriver
  # spend is separately attributable in Cost Explorer / Resource Groups.
  default_tags {
    tags = {
      Project     = "ClaudeDriver"
      Environment = var.environment
      ManagedBy   = "terraform"
      Owner       = var.budget_notification_email
    }
  }
}
