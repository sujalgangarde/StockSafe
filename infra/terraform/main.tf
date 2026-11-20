# ---------- network: the default VPC, public subnets, NO NAT gateway (a NAT gateway costs money every hour) ----------
data "aws_vpc" "default" { default = true }

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# ---------- secrets (one secret, several keys) ----------
resource "random_password" "db_owner" {
  length  = 24
  special = false
}

resource "random_password" "db_app" {
  length  = 24
  special = false
}

resource "random_password" "jwt" {
  length  = 48
  special = false
}

resource "random_password" "admin_key" {
  length  = 32
  special = false
}

resource "random_password" "alerts_admin" {
  length  = 32
  special = false
}

resource "random_password" "mq" {
  length  = 24
  special = false
}

resource "aws_secretsmanager_secret" "app" {
  name                    = "${var.project}/app"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id
  secret_string = jsonencode({
    db_owner_password = random_password.db_owner.result
    db_app_password   = random_password.db_app.result
    jwt_secret        = random_password.jwt.result
    admin_key         = random_password.admin_key.result
    alerts_admin      = random_password.alerts_admin.result
    rabbit_password   = var.use_amazon_mq ? random_password.mq.result : var.external_rabbit_password
    razorpay_key_id   = ""
    razorpay_secret   = ""
    razorpay_webhook  = "change-me"
    razorpay_plan_pro = ""
  })
}

# ---------- security groups ----------
resource "aws_security_group" "alb" {
  name   = "${var.project}-alb"
  vpc_id = data.aws_vpc.default.id
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "svc" {
  name   = "${var.project}-svc"
  vpc_id = data.aws_vpc.default.id
  ingress {
    from_port       = 8080
    to_port         = 8081
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "db" {
  name   = "${var.project}-db"
  vpc_id = data.aws_vpc.default.id
  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.svc.id]
  }
  ingress {
    description = "your laptop, for the one-time role script"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.my_ip_cidr]
  }
}

resource "aws_security_group" "mq" {
  name   = "${var.project}-mq"
  vpc_id = data.aws_vpc.default.id
  ingress {
    from_port       = 5671
    to_port         = 5671
    protocol        = "tcp"
    security_groups = [aws_security_group.svc.id]
  }
}

# ---------- database: RDS PostgreSQL 16, single AZ (Multi-AZ doubles the cost) ----------
resource "aws_db_instance" "pg" {
  identifier              = "${var.project}-pg"
  engine                  = "postgres"
  engine_version          = "16"
  instance_class          = var.db_instance_class
  allocated_storage       = 20
  db_name                 = "stocksafe"
  username                = "stocksafe"
  password                = random_password.db_owner.result
  multi_az                = false
  publicly_accessible     = true # so you can run scripts/rds-init.sql once. Set to false afterwards.
  vpc_security_group_ids  = [aws_security_group.db.id]
  backup_retention_period = 1
  skip_final_snapshot     = true
  deletion_protection     = false
}

# ---------- RabbitMQ ----------
resource "aws_mq_broker" "rabbit" {
  count                      = var.use_amazon_mq ? 1 : 0
  broker_name                = "${var.project}-rabbit"
  engine_type                = "RabbitMQ"
  engine_version             = "3.13"
  host_instance_type         = "mq.t3.micro"
  deployment_mode            = "SINGLE_INSTANCE"
  publicly_accessible        = false
  auto_minor_version_upgrade = true
  security_groups            = [aws_security_group.mq.id]
  subnet_ids                 = [data.aws_subnets.default.ids[0]]
  user {
    username = "stocksafe"
    password = random_password.mq.result
  }
}

locals {
  rabbit_host = var.use_amazon_mq ? replace(replace(aws_mq_broker.rabbit[0].instances[0].endpoints[0], "amqps://", ""), ":5671", "") : var.external_rabbit_host
  rabbit_user = var.use_amazon_mq ? "stocksafe" : var.external_rabbit_user
  secret_arn  = aws_secretsmanager_secret.app.arn
}

# ---------- registries and logs ----------
resource "aws_ecr_repository" "svc" {
  for_each             = toset(["core", "alerts"])
  name                 = "${var.project}-${each.key}"
  image_tag_mutability = "MUTABLE"
  force_delete         = true
}

resource "aws_cloudwatch_log_group" "svc" {
  for_each          = toset(["core", "alerts"])
  name              = "/ecs/${var.project}-${each.key}"
  retention_in_days = 14
}

# ---------- web app on S3 (plain HTTP website; put CloudFront in front for HTTPS) ----------
resource "random_id" "bucket" { byte_length = 4 }

resource "aws_s3_bucket" "web" {
  bucket        = "${var.project}-web-${random_id.bucket.hex}"
  force_destroy = true
}

resource "aws_s3_bucket_website_configuration" "web" {
  bucket = aws_s3_bucket.web.id
  index_document { suffix = "index.html" }
  error_document { key = "index.html" } # single-page app: unknown paths load the app
}

resource "aws_s3_bucket_public_access_block" "web" {
  bucket                  = aws_s3_bucket.web.id
  block_public_acls       = false
  block_public_policy     = false
  ignore_public_acls      = false
  restrict_public_buckets = false
}

resource "aws_s3_bucket_policy" "web" {
  bucket     = aws_s3_bucket.web.id
  depends_on = [aws_s3_bucket_public_access_block.web]
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = "*"
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.web.arn}/*"
    }]
  })
}

# ---------- load balancer: one entry point, path routing to the two services ----------
resource "aws_lb" "main" {
  name               = "${var.project}-alb"
  load_balancer_type = "application"
  subnets            = data.aws_subnets.default.ids
  security_groups    = [aws_security_group.alb.id]
  idle_timeout       = 120 # live-update streams stay open
}

resource "aws_lb_target_group" "svc" {
  for_each    = { core = 8080, alerts = 8081 }
  name        = "${var.project}-${each.key}"
  port        = each.value
  protocol    = "HTTP"
  vpc_id      = data.aws_vpc.default.id
  target_type = "ip"
  health_check {
    path                = "/actuator/health/liveness"
    healthy_threshold   = 2
    unhealthy_threshold = 5
    interval            = 15
  }
  deregistration_delay = 20
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.svc["core"].arn
  }
}

resource "aws_lb_listener_rule" "alerts" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 10
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.svc["alerts"].arn
  }
  condition {
    path_pattern {
      values = ["/api/v1/alerts*", "/api/v1/alert-rules*", "/api/v1/forecast*", "/api/v1/notifications*", "/internal/*"]
    }
  }
}

# ---------- IAM ----------
data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "exec" {
  name               = "${var.project}-ecs-exec"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "exec" {
  role       = aws_iam_role.exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "exec_secrets" {
  name = "read-app-secret"
  role = aws_iam_role.exec.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = local.secret_arn }]
  })
}

# ---------- ECS (Fargate) ----------
resource "aws_ecs_cluster" "main" { name = var.project }

locals {
  common_env = [
    { name = "SPRING_PROFILES_ACTIVE", value = "json" },
    { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.pg.address}:5432/stocksafe" },
    { name = "DB_APP_USER", value = "stocksafe_app" },
    { name = "DB_OWNER_USER", value = "stocksafe" },
    { name = "RABBIT_HOST", value = local.rabbit_host },
    { name = "RABBIT_PORT", value = "5671" },
    { name = "RABBIT_USER", value = local.rabbit_user },
    { name = "SPRING_RABBITMQ_SSL_ENABLED", value = "true" },
    { name = "MANAGEMENT_TRACING_ENABLED", value = "false" }, # no trace collector in this setup
    { name = "CORS_ORIGINS", value = "http://${aws_s3_bucket_website_configuration.web.website_endpoint}" },
  ]
  common_secrets = [
    { name = "DB_APP_PASSWORD", valueFrom = "${local.secret_arn}:db_app_password::" },
    { name = "DB_OWNER_PASSWORD", valueFrom = "${local.secret_arn}:db_owner_password::" },
    { name = "RABBIT_PASSWORD", valueFrom = "${local.secret_arn}:rabbit_password::" },
    { name = "JWT_SECRET", valueFrom = "${local.secret_arn}:jwt_secret::" },
  ]
  core_env = concat(local.common_env, [
    { name = "RATELIMIT_STORE", value = "memory" }, # no Redis in this setup
    { name = "BILLING_MOCK", value = var.billing_mock },
  ])
  core_secrets = concat(local.common_secrets, [
    { name = "PLATFORM_ADMIN_KEY", valueFrom = "${local.secret_arn}:admin_key::" },
    { name = "RAZORPAY_KEY_ID", valueFrom = "${local.secret_arn}:razorpay_key_id::" },
    { name = "RAZORPAY_KEY_SECRET", valueFrom = "${local.secret_arn}:razorpay_secret::" },
    { name = "RAZORPAY_WEBHOOK_SECRET", valueFrom = "${local.secret_arn}:razorpay_webhook::" },
    { name = "RAZORPAY_PLAN_PRO", valueFrom = "${local.secret_arn}:razorpay_plan_pro::" },
  ])
  alerts_secrets = concat(local.common_secrets, [
    { name = "ALERTS_ADMIN_TOKEN", valueFrom = "${local.secret_arn}:alerts_admin::" },
  ])
}

resource "aws_ecs_task_definition" "svc" {
  for_each                 = { core = 8080, alerts = 8081 }
  family                   = "${var.project}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.exec.arn
  container_definitions = jsonencode([{
    name         = each.key
    image        = "${aws_ecr_repository.svc[each.key].repository_url}:latest"
    essential    = true
    portMappings = [{ containerPort = each.value }]
    environment  = each.key == "core" ? local.core_env : local.common_env
    secrets      = each.key == "core" ? local.core_secrets : local.alerts_secrets
    stopTimeout  = 30
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.svc[each.key].name
        awslogs-region        = var.region
        awslogs-stream-prefix = each.key
      }
    }
  }])
}

resource "aws_ecs_service" "svc" {
  for_each                          = { core = 8080, alerts = 8081 }
  name                              = "${var.project}-${each.key}"
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.svc[each.key].arn
  desired_count                     = 1
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 120
  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.svc.id]
    assign_public_ip = true # lets the task pull images without a NAT gateway
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.svc[each.key].arn
    container_name   = each.key
    container_port   = each.value
  }
  depends_on = [aws_lb_listener.http]
  lifecycle { ignore_changes = [desired_count] }
}
