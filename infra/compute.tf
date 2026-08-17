# D2: One small ECS Fargate task behind an ALB. The ALB terminates public TLS.
# Operator/web listener uses server TLS only (WebAuthn at the app layer); the
# agent listener additionally performs mutual-TLS client-certificate verification
# against the device-CA trust store (Principles I / IV).

# --- CloudWatch Logs -------------------------------------------------------

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/claudedriver/${var.environment}/backend"
  retention_in_days = 14

  tags = {
    Name      = "claudedriver-backend-logs"
    Component = "observability"
  }
}

# --- ECS cluster / task / service -----------------------------------------

resource "aws_ecs_cluster" "main" {
  name = "claudedriver"

  setting {
    name  = "containerInsights"
    value = "disabled"
  }

  tags = {
    Name      = "claudedriver"
    Component = "compute"
  }
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "claudedriver-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = "backend"
      image     = var.container_image
      essential = true

      portMappings = [
        {
          containerPort = var.backend_container_port
          protocol      = "tcp"
        }
      ]

      environment = [
        { name = "CLAUDEDRIVER_ENV", value = var.environment },
        { name = "BACKEND_HOST", value = "0.0.0.0" },
        { name = "BACKEND_PORT", value = tostring(var.backend_container_port) },
        { name = "WEBAUTHN_RP_ID", value = var.domain_name },
        { name = "WEBAUTHN_RP_NAME", value = "ClaudeDriver" },
        { name = "WEBAUTHN_ORIGIN", value = "https://${var.domain_name}" },
        { name = "AGENT_RUNTIMES_BUCKET", value = var.agent_runtimes_bucket }
      ]

      # Secrets injected from SSM Parameter Store at task start (D8).
      secrets = [
        { name = "DATABASE_URL", valueFrom = aws_ssm_parameter.database_url.arn },
        { name = "DATABASE_USER", valueFrom = aws_ssm_parameter.database_username.arn },
        { name = "DATABASE_PASSWORD", valueFrom = aws_ssm_parameter.database_password.arn },
        { name = "SESSION_SIGNING_KEY", valueFrom = aws_ssm_parameter.session_signing_key.arn },
        { name = "OPERATOR_BOOTSTRAP_CODE", valueFrom = aws_ssm_parameter.operator_bootstrap_code.arn },
        { name = "CA_CERT_PEM", valueFrom = aws_ssm_parameter.ca_cert_pem.arn },
        { name = "CA_KEY_PEM", valueFrom = aws_ssm_parameter.ca_key_pem.arn }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.backend.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "backend"
        }
      }
    }
  ])

  tags = {
    Name      = "claudedriver-backend"
    Component = "compute"
  }
}

resource "aws_ecs_service" "backend" {
  name            = "claudedriver-backend"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.ecs_task.id]
    assign_public_ip = true # default-VPC public subnets: needed to pull image / reach SSM
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.backend.arn
    container_name   = "backend"
    container_port   = var.backend_container_port
  }

  # Give the task time to become healthy behind the ALB before the LB check counts.
  health_check_grace_period_seconds = 60

  depends_on = [
    aws_lb_listener.web,
    aws_lb_listener.agent
  ]

  tags = {
    Name      = "claudedriver-backend"
    Component = "compute"
  }
}

# --- Application Load Balancer ---------------------------------------------

resource "aws_lb" "main" {
  name               = "claudedriver-alb"
  load_balancer_type = "application"
  internal           = false
  security_groups    = [aws_security_group.alb.id]
  subnets            = data.aws_subnets.default.ids

  idle_timeout = 3600 # long idle for persistent WebSockets (Principle V)

  tags = {
    Name      = "claudedriver-alb"
    Component = "network"
  }
}

# WebSocket-friendly target group: long-lived connections + stickiness so a
# reconnect can land on a stable target within the small fleet.
resource "aws_lb_target_group" "backend" {
  name        = "claudedriver-backend"
  port        = var.backend_container_port
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = data.aws_vpc.default.id

  deregistration_delay = 30

  health_check {
    path                = "/healthz"
    port                = "traffic-port"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  stickiness {
    type            = "lb_cookie"
    enabled         = true
    cookie_duration = 86400
  }

  tags = {
    Name      = "claudedriver-backend"
    Component = "network"
  }
}

# Operator / web HTTPS listener: server TLS only. Operators authenticate with
# self-hosted WebAuthn passkeys at the application layer (D4), not client certs.
resource "aws_lb_listener" "web" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.acm_certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }

  tags = {
    Name = "claudedriver-web"
  }
}

# Device-CA trust store for agent mTLS client-certificate verification.
# The CA bundle (PEM) is uploaded to S3 by the operator before apply (D3).
resource "aws_lb_trust_store" "agents" {
  name                             = "claudedriver-agents"
  ca_certificates_bundle_s3_bucket = var.agent_ca_bundle_s3_bucket
  ca_certificates_bundle_s3_key    = var.agent_ca_bundle_s3_key

  tags = {
    Name = "claudedriver-agents"
  }
}

# Agent HTTPS listener: mutual TLS. The ALB verifies the agent's client cert
# against the device-CA trust store and refuses unverified/forged certs. This
# is the network-layer expression of Principle I/IV (per-device mTLS identity).
resource "aws_lb_listener" "agent" {
  load_balancer_arn = aws_lb.main.arn
  port              = 8443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.acm_certificate_arn

  mutual_authentication {
    mode            = "verify"
    trust_store_arn = aws_lb_trust_store.agents.arn
  }

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }

  tags = {
    Name = "claudedriver-agent"
  }
}
