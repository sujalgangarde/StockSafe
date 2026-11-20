output "api_base_url" {
  value = "http://${aws_lb.main.dns_name}"
}

output "web_url" {
  value = "http://${aws_s3_bucket_website_configuration.web.website_endpoint}"
}

output "s3_bucket" {
  value = aws_s3_bucket.web.bucket
}

output "ecr_core" {
  value = aws_ecr_repository.svc["core"].name
}

output "ecr_alerts" {
  value = aws_ecr_repository.svc["alerts"].name
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "rds_address" {
  value = aws_db_instance.pg.address
}

output "secret_name" {
  value = aws_secretsmanager_secret.app.name
}
