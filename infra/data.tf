# D6: Amazon RDS for PostgreSQL db.t4g.micro, single-AZ, small storage.
# D8: DB URL + secrets stored as SSM Parameter Store SecureString under /claudedriver/.

resource "aws_db_subnet_group" "main" {
  name       = "claudedriver-db"
  subnet_ids = data.aws_subnets.default.ids

  tags = {
    Name = "claudedriver-db"
  }
}

resource "aws_db_instance" "main" {
  identifier     = "claudedriver-postgres"
  engine         = "postgres"
  engine_version = "16"
  instance_class = "db.t4g.micro"

  allocated_storage     = 20
  max_allocated_storage = 50
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = var.db_name
  username = var.db_username
  password = var.db_password
  port     = 5432

  multi_az               = false
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false

  # Enforce TLS (sslmode=require is set on the client via the SSM DB URL).
  parameter_group_name = aws_db_parameter_group.main.name

  backup_retention_period = 7
  deletion_protection     = false
  skip_final_snapshot     = true
  apply_immediately       = true

  tags = {
    Name = "claudedriver-postgres"
  }
}

# Require SSL connections at the server, matching the client sslmode=require.
resource "aws_db_parameter_group" "main" {
  name   = "claudedriver-postgres16"
  family = "postgres16"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  tags = {
    Name = "claudedriver-postgres16"
  }
}

# --- Secrets & config in SSM Parameter Store (SecureString) ----------------

# JDBC URL with sslmode=require, consumed by the backend at boot.
resource "aws_ssm_parameter" "database_url" {
  name        = "/claudedriver/${var.environment}/DATABASE_URL"
  description = "PostgreSQL JDBC URL (sslmode=require) for the ClaudeDriver backend."
  type        = "SecureString"
  value       = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${var.db_name}?sslmode=require"

  tags = {
    Name = "claudedriver-database-url"
  }
}

resource "aws_ssm_parameter" "database_password" {
  name        = "/claudedriver/${var.environment}/DATABASE_PASSWORD"
  description = "RDS master password for the ClaudeDriver backend."
  type        = "SecureString"
  value       = var.db_password

  tags = {
    Name = "claudedriver-database-password"
  }
}

resource "aws_ssm_parameter" "database_username" {
  name        = "/claudedriver/${var.environment}/DATABASE_USERNAME"
  description = "RDS master username for the ClaudeDriver backend."
  type        = "SecureString"
  value       = var.db_username

  tags = {
    Name = "claudedriver-database-username"
  }
}

resource "aws_ssm_parameter" "session_signing_key" {
  name        = "/claudedriver/${var.environment}/SESSION_SIGNING_KEY"
  description = "Secret key used by the ClaudeDriver backend to sign operator session tokens."
  type        = "SecureString"
  value       = var.session_signing_key

  tags = {
    Name = "claudedriver-session-signing-key"
  }
}

resource "aws_ssm_parameter" "operator_bootstrap_code" {
  name        = "/claudedriver/${var.environment}/OPERATOR_BOOTSTRAP_CODE"
  description = "One-time bootstrap code used by the ClaudeDriver backend to enroll the first operator."
  type        = "SecureString"
  value       = var.operator_bootstrap_code

  tags = {
    Name = "claudedriver-operator-bootstrap-code"
  }
}

resource "aws_ssm_parameter" "ca_cert_pem" {
  name        = "/claudedriver/${var.environment}/CA_CERT_PEM"
  description = "Persistent device-CA certificate (PEM) for the ClaudeDriver backend."
  type        = "SecureString"
  value       = var.ca_cert_pem

  tags = {
    Name = "claudedriver-ca-cert-pem"
  }
}

resource "aws_ssm_parameter" "ca_key_pem" {
  name        = "/claudedriver/${var.environment}/CA_KEY_PEM"
  description = "Persistent device-CA private key (PEM) for the ClaudeDriver backend."
  type        = "SecureString"
  value       = var.ca_key_pem

  tags = {
    Name = "claudedriver-ca-key-pem"
  }
}
