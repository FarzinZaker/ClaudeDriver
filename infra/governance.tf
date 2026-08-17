# Least-privilege IAM (Principle I), project resource grouping + AWS Budget
# (Principle VII / FR-021/FR-022).

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  ssm_prefix_arn = "arn:aws:ssm:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:parameter/claudedriver/*"
}

# --- ECS task EXECUTION role ------------------------------------------------
# Used by the ECS agent to pull the image, write logs, and read the SSM
# secrets referenced in the task definition's `secrets` block.

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_execution" {
  name               = "claudedriver-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json

  tags = {
    Name = "claudedriver-ecs-execution"
  }
}

# AWS-managed policy grants ECR pull + CloudWatch Logs create/put.
resource "aws_iam_role_policy_attachment" "ecs_execution_managed" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Scoped read of the ClaudeDriver SSM SecureString parameters only.
data "aws_iam_policy_document" "ssm_read" {
  statement {
    sid = "ReadClaudeDriverParams"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath"
    ]
    resources = [local.ssm_prefix_arn]
  }
}

resource "aws_iam_role_policy" "ecs_execution_ssm" {
  name   = "claudedriver-ssm-read"
  role   = aws_iam_role.ecs_execution.id
  policy = data.aws_iam_policy_document.ssm_read.json
}

# --- ECS TASK role ----------------------------------------------------------
# The application's own identity at runtime: scoped SSM read + its log stream.

resource "aws_iam_role" "ecs_task" {
  name               = "claudedriver-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json

  tags = {
    Name = "claudedriver-ecs-task"
  }
}

data "aws_iam_policy_document" "task_runtime" {
  statement {
    sid = "ReadClaudeDriverParams"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath"
    ]
    resources = [local.ssm_prefix_arn]
  }

  statement {
    sid = "WriteOwnLogs"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents"
    ]
    resources = ["${aws_cloudwatch_log_group.backend.arn}:*"]
  }

  # Read the self-contained per-OS agent runtimes the backend wraps into per-machine installers.
  dynamic "statement" {
    for_each = var.agent_runtimes_bucket == "" ? [] : [1]
    content {
      sid       = "ReadAgentRuntimes"
      actions   = ["s3:GetObject"]
      resources = ["arn:aws:s3:::${var.agent_runtimes_bucket}/*"]
    }
  }
}

resource "aws_iam_role_policy" "task_runtime" {
  name   = "claudedriver-task-runtime"
  role   = aws_iam_role.ecs_task.id
  policy = data.aws_iam_policy_document.task_runtime.json
}

# --- Project resource group (cost attribution) ------------------------------
# Collects every resource carrying the Project=ClaudeDriver tag (D9).

resource "aws_resourcegroups_group" "project" {
  name        = "claudedriver"
  description = "All ClaudeDriver resources grouped by the Project cost-allocation tag"

  resource_query {
    query = jsonencode({
      ResourceTypeFilters = ["AWS::AllSupported"]
      TagFilters = [
        {
          Key    = "Project"
          Values = ["ClaudeDriver"]
        }
      ]
    })
  }

  tags = {
    Name = "claudedriver"
  }
}

# --- Monthly budget + alert (Principle VII) --------------------------------
# Scoped to the Project=ClaudeDriver tag so it tracks only this project's spend.

resource "aws_budgets_budget" "monthly" {
  # AWS Budgets can only be created by the payer/management account. When this
  # account is a linked member of an Organization, set enable_budget=false and
  # create the tag-scoped budget in the payer account (see deploy/RUNBOOK.md).
  count = var.enable_budget ? 1 : 0

  name         = "claudedriver-monthly"
  budget_type  = "COST"
  limit_amount = var.monthly_budget_limit_usd
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_filter {
    name   = "TagKeyValue"
    values = ["user:Project$ClaudeDriver"]
  }

  # Alert when actual spend crosses 80% of the ceiling.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_notification_email]
  }

  # Early warning when forecast spend is projected to exceed the ceiling.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.budget_notification_email]
  }
}
