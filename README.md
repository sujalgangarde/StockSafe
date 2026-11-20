# StockSafe: Stop waste. Stop stockouts.

Multi-tenant inventory and expiry app for small shops (Java 21, Spring Boot, PostgreSQL 16 with row-level security, RabbitMQ, React).

IMPORTANT: this code was written without being compiled or run. Run the steps below. If anything fails,
paste the error and ask for the corrected full file.

## Quick start (Windows, PowerShell, from this folder)
Install once: JDK 21, Maven 3.9+, Node 20+, Docker Desktop.

1. Start the databases and queue (free, local):

       docker compose up -d postgres redis rabbitmq

   If you ever see `role "stocksafe_app" does not exist`: `docker compose down -v`, then run the line above again.

2. Start the main service (terminal 1):

       mvn -pl stocksafe-core spring-boot:run

3. Start the web app (terminal 2):

       cd frontend
       npm install
       npm run dev

4. Open http://localhost:5173 . Sign up, create a shop, add a product, receive stock, sell.

Optional: the alerts service (expiry / low-stock alerts, emails are mocked) in terminal 3:

       mvn -pl stocksafe-alerts spring-boot:run

Billing runs in mock mode (no Razorpay needed). On the Billing page use the "Test mode" buttons.

## Tests (needs Docker running)
    mvn test

## More
- Everything in containers: `docker compose --profile full up -d --build`
- Monitoring: Grafana http://localhost:3000 (admin/admin), Prometheus :9090, Jaeger :16686, RabbitMQ :15672
- Failure test (kill alerts service): `scripts/failure-test.ps1`
- 100,000-row import timing: `scripts/import-100k.ps1`
- Load test: `scripts/k6/load.js`  |  EXPLAIN before/after: `scripts/perf/explain.sql`
- AWS: `infra/terraform` (run the Terraform steps only when you want to deploy; they cost money)
- Platform admin page: http://localhost:5173/admin (server needs PLATFORM_ADMIN_KEY set)

## Layout
    stocksafe-core/    main API: auth, tenants, shops, products, stock (FEFO), imports, billing, SSE
    stocksafe-alerts/  second service: reads events, raises alerts, sends notifications
    frontend/          React + Vite PWA
    docker/            Postgres role script, Prometheus, Grafana
    infra/terraform/   AWS (ECS Fargate, RDS, ALB, S3, ECR)
    scripts/           failure test, import test, k6, EXPLAIN

    Browser --> stocksafe-core --> PostgreSQL (RLS)
                    | outbox
                    v
                RabbitMQ --> stocksafe-alerts --> email / WhatsApp (mock)
                    ^---------- alert events (live updates to the browser)
