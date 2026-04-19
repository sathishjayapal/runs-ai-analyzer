# ECS / Fargate Notes

This service can run on AWS Fargate as a stateless container if you externalize its stateful dependencies:

- PostgreSQL with `pgvector`
- RabbitMQ
- Anthropic API access
- Ollama-compatible embedding endpoint
- Spring Cloud Config Server credentials

## Recommended Production Shape

- **App runtime**: AWS ECS on Fargate
- **Database**: PostgreSQL with `pgvector`
  - preferred: managed Postgres that supports `pgvector`
  - alternative: EC2-hosted Postgres with `pgvector`
- **RabbitMQ**: managed broker or EC2-hosted RabbitMQ
- **Config**: existing `sathishproject-config-server`
- **Container image**: push the repo `Dockerfile` image to ECR

## Required Environment Variables

- `SPRING_PROFILES_ACTIVE=prod`
- `CONFIG_SERVER_URL`
- `SPRING_CLOUD_CONFIG_USERNAME`
- `SPRING_CLOUD_CONFIG_PASSWORD`
- `RUNS_AI_ANALYZER_DB_URL`
- `RUNS_AI_ANALYZER_DB_USER`
- `RUNS_AI_ANALYZER_DB_PASSWORD`
- `RABBITMQ_HOST`
- `RABBITMQ_PORT`
- `RABBITMQ_USERNAME`
- `RABBITMQ_PASSWORD`
- `ANTHROPIC_API_KEY`
- `OLLAMA_BASE_URL`

## Operational Notes

- Keep Flyway enabled so schema changes apply automatically on deploy.
- Point `RUNS_AI_ANALYZER_DB_URL` at a `pgvector`-enabled database before first startup.
- If you do not have a production-grade Ollama endpoint, replace the embedding strategy before scaling this service.
- RabbitMQ exchange and queue provisioning should continue to live with `eventstracker`, not this service.

## Deploy Sequence

1. Build and push the image to ECR.
2. Provision the database and broker.
3. Ensure config server can serve `runs-ai-analyzer-prod.yml`.
4. Create the ECS task definition from `task-definition.json`.
5. Create or update the ECS service.
