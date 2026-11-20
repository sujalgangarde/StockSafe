variable "region" {
  type    = string
  default = "ap-south-1"
}

variable "project" {
  type    = string
  default = "stocksafe"
}

variable "my_ip_cidr" {
  type        = string
  description = "Your public IP as x.x.x.x/32. Only this address can reach the database (to run the one-time role script)."
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "use_amazon_mq" {
  type        = bool
  default     = true
  description = "true = create an Amazon MQ RabbitMQ broker (costs money, not free-tier safe). false = use external_rabbit_* below (for example a free CloudAMQP plan)."
}

variable "external_rabbit_host" {
  type    = string
  default = ""
}

variable "external_rabbit_user" {
  type    = string
  default = "stocksafe"
}

variable "external_rabbit_password" {
  type      = string
  default   = ""
  sensitive = true
}

variable "task_cpu" {
  type    = number
  default = 512
}

variable "task_memory" {
  type    = number
  default = 1024
}

variable "billing_mock" {
  type        = string
  default     = "true"
  description = "Keep true until you add real Razorpay keys to the secret."
}
