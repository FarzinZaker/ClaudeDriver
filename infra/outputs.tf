output "alb_dns_name" {
  description = "Public DNS name of the ALB. Point a CNAME/ALIAS for var.domain_name at this."
  value       = aws_lb.main.dns_name
}

output "alb_zone_id" {
  description = "Hosted-zone ID of the ALB, for Route 53 ALIAS records."
  value       = aws_lb.main.zone_id
}

output "rds_endpoint" {
  description = "RDS PostgreSQL connection endpoint (host:port)."
  value       = aws_db_instance.main.endpoint
}

output "ecs_cluster_name" {
  description = "Name of the ECS cluster."
  value       = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  description = "Name of the backend ECS service."
  value       = aws_ecs_service.backend.name
}

output "agent_trust_store_arn" {
  description = "ARN of the device-CA trust store used for agent mTLS verification."
  value       = aws_lb_trust_store.agents.arn
}

output "resource_group_name" {
  description = "AWS Resource Group collecting all Project=ClaudeDriver resources."
  value       = aws_resourcegroups_group.project.name
}
