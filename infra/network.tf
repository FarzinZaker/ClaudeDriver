# Cost-conscious networking: reuse the account's default VPC and default subnets
# rather than provisioning NAT gateways / a bespoke VPC for a <=10-machine fleet.

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# --- Security groups -------------------------------------------------------

# ALB: public HTTPS ingress only (operator/web listener + agent mTLS listener).
resource "aws_security_group" "alb" {
  name        = "claudedriver-alb"
  description = "ClaudeDriver ALB - HTTPS ingress"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "Operator/web HTTPS from anywhere (WebAuthn at app layer)"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "Agent mTLS HTTPS from anywhere (client-cert verified at ALB trust store)"
    from_port   = 8443
    to_port     = 8443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "All egress (to ECS tasks and health checks)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "claudedriver-alb"
  }
}

# ECS task: ingress only from the ALB on the backend port; egress open so the
# task can reach RDS, SSM, ECR, and CloudWatch Logs.
resource "aws_security_group" "ecs_task" {
  name        = "claudedriver-ecs-task"
  description = "ClaudeDriver ECS task - ingress from ALB, egress only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "Backend port from ALB only"
    from_port       = var.backend_container_port
    to_port         = var.backend_container_port
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    description = "All outbound (RDS, SSM, ECR, CloudWatch)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "claudedriver-ecs-task"
  }
}

# RDS: ingress only from the ECS task security group on the Postgres port.
resource "aws_security_group" "rds" {
  name        = "claudedriver-rds"
  description = "ClaudeDriver RDS - ingress from ECS task only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "PostgreSQL from ECS task only"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_task.id]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "claudedriver-rds"
  }
}
