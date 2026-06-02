# Runs AI Analyzer

A Spring Boot microservice that provides AI-powered analysis of Garmin running data using Anthropic Claude, with
semantic search capabilities and event-driven integration with the runs-app ecosystem.

## What It Does

This service accepts Garmin running activity data and generates intelligent, structured coaching insights using Claude.
Analyses are stored in a PgVector-backed vector store for semantic search and caching to avoid redundant API calls.

### Core Features

- **AI-Powered Analysis**: Claude generates structured JSON insights including summary, specific coaching feedback,
  recommendations, risk flags, and confidence scores
- **Semantic Search**: Search past analyses using natural language queries via PgVector embeddings
- **RAG Caching**: Stores and retrieves similar analyses to avoid redundant LLM calls
- **Event-Driven Processing**: Consumes Garmin events from RabbitMQ, processes with idempotency guarantees
- **Batch Processing**: Efficiently batches analysis requests for cost optimization
- **Run Journal**: Store and retrieve personal running journal entries with embeddings
- **Error Recovery**: Dead letter queue, retry logic, and reconciliation for failed events
- **REST API**: Full OpenAPI/Swagger documentation with health checks

## Tech Stack

- **Java 21** with Spring Boot 4.0.1
- **Spring AI** for Claude and Ollama integration
- **PostgreSQL** with PgVector for vector storage
- **RabbitMQ** for event-driven integration
- **Flyway** for database migrations
- **SpringDoc OpenAPI** for API documentation

## Quick Start

### Prerequisites

- JDK 21+
- Maven 3.9+
- Docker & Docker Compose
- Anthropic API Key
- PostgreSQL with pgvector extension
- RabbitMQ

### Setup

```bash
# 1. Start infrastructure
docker compose up -d postgres rabbitmq ollama

# 2. Set environment variables
export ANTHROPIC_API_KEY=your-key-here
export SPRING_PROFILES_ACTIVE=local

# 3. Run the application
./mvnw spring-boot:run
```

The service starts on **port 8081**.

## API Endpoints

### Run Analysis

| Method | Endpoint                          | Purpose                     |
|--------|-----------------------------------|-----------------------------|
| POST   | `/api/v1/analysis/analyze`        | Analyze multiple runs       |
| POST   | `/api/v1/analysis/check`          | Check if data contains runs |
| POST   | `/api/v1/analysis/analyze/single` | Analyze a single run        |

### RAG Search & Retrieval

| Method | Endpoint                                 | Purpose                          |
|--------|------------------------------------------|----------------------------------|
| POST   | `/api/v1/rag/search`                     | Semantic search of past analyses |
| GET    | `/api/v1/rag/recent?limit=10`            | Get recent analyses              |
| GET    | `/api/v1/rag/document/{id}`              | Get analysis by ID               |
| GET    | `/api/v1/rag/activity/{id}`              | Find analyses by activity        |
| GET    | `/api/v1/rag/distance?minDistanceKm=5.0` | Find analyses by distance        |

### Run Journal

| Method | Endpoint                       | Purpose                      |
|--------|--------------------------------|------------------------------|
| POST   | `/api/v1/journal/entries`      | Create journal entry         |
| GET    | `/api/v1/journal/entries`      | List journal entries         |
| GET    | `/api/v1/journal/entries/{id}` | Get entry by ID              |
| GET    | `/api/v1/journal/search`       | Search entries by similarity |

### Documentation

- **Swagger UI**: http://localhost:8081/swagger-ui.html
- **OpenAPI Spec**: http://localhost:8081/api-docs
- **Health Check**: http://localhost:8081/actuator/health

## Example: Analyze Runs

```bash
curl -X POST http://localhost:8081/api/v1/analysis/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "runs": [
      {
        "activityId": "12345",
        "activityDate": "2024-01-15",
        "activityType": "running",
        "activityName": "Morning Run",
        "distance": 5.5,
        "elapsedTime": "00:28:30",
        "maxHeartRate": 165,
        "calories": 450
      }
    ],
    "forceRefresh": false
  }'
```

## Example Response

```json
{
  "documentId": "0d2d5f4e-df40-4d2b-91e8-f9f7c8a63145",
  "containsRunData": true,
  "summary": "Your recent runs show steady aerobic work with effective intensity distribution.",
  "insights": [
    {
      "category": "Pace",
      "observation": "Your average pace stayed controlled relative to heart-rate load.",
      "recommendation": "Keep easy-day pace restrained so quality sessions stay sharp."
    }
  ],
  "recommendations": [
    "Repeat comparison after next training block",
    "Tag workout intent in Garmin notes for better coaching prompts"
  ],
  "riskFlags": [
    "Watch recovery if back-to-back hard efforts continue"
  ],
  "confidenceScore": 86,
  "metrics": {
    "totalRuns": 1,
    "totalDistanceKm": 5.5,
    "totalDuration": "00:28:30",
    "averagePaceMinPerKm": 5.18,
    "averageHeartRate": 165,
    "totalCalories": 450
  },
  "analyzedAt": "2024-01-15T10:30:00Z",
  "cachedResult": false
}
```

## Event-Driven Integration

### How It Works

1. **runs-app** imports Garmin CSV files and publishes events to RabbitMQ
2. **runs-ai-analyzer** listens on the queue and processes events asynchronously
3. Events are queued with **idempotency guarantees** (same event never analyzed twice)
4. Every 30 seconds, the batch processor analyzes accumulated runs
5. Failed events are automatically retried with exponential backoff
6. Reconciliation job runs every 6 hours to catch missed events

### Configuration

Key properties in `application.yaml` (via Spring Cloud Config):

```yaml
analysis:
  batch:
    size: 5                    # Minimum runs to trigger analysis
    interval-ms: 30000         # Batch collection window
    window-minutes: 60         # Maximum wait time

reconciliation:
  enabled: true
  max-retries: 3               # Retry attempts for failed events
  retry-delay-minutes: 30      # Wait between retries
  lookback-days: 7             # Historical lookback for catch-up
  cron: "0 0 */6 * * *"        # Every 6 hours
```

## Database Schema

### run_analysis_document

Stores all AI-generated analyses with embeddings for semantic search.

### analysis_processing_log

Tracks event processing for idempotency and error recovery:

- `activity_id` + `database_id` unique constraint ensures no duplicates
- Status tracking: PENDING → PROCESSING → COMPLETED/FAILED
- Retry count and error messages for troubleshooting

### run_journal_entry

Personal running journal entries with embedding vectors for similarity search.

## Search Examples

### Semantic Search

Find analyses similar to a query:

```bash
curl -X POST http://localhost:8081/api/v1/rag/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "high intensity interval training with long recovery",
    "topK": 5
  }'
```

### By Activity

Find all analyses containing a specific activity:

```bash
curl http://localhost:8081/api/v1/rag/activity/12345
```

### By Distance

Find analyses with runs of at least 10 km:

```bash
curl "http://localhost:8081/api/v1/rag/distance?minDistanceKm=10.0"
```

## Monitoring & Troubleshooting

### Check Processing Status

```sql
SELECT processing_status, COUNT(*) as count
FROM analysis_processing_log
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY processing_status;
```

### Find Failed Events

```sql
SELECT activity_id, retry_count, error_message, created_at
FROM analysis_processing_log
WHERE processing_status = 'FAILED'
ORDER BY created_at DESC
LIMIT 10;
```

### Health Check

```bash
curl http://localhost:8081/actuator/health
```

## Environment Variables

| Variable                 | Default                  | Purpose                   |
|--------------------------|--------------------------|---------------------------|
| `ANTHROPIC_API_KEY`      | -                        | Claude API key (required) |
| `SPRING_PROFILES_ACTIVE` | `local`                  | Active profile            |
| `RUNS_APP_BASE_URL`      | `http://localhost:8080`  | runs-app API endpoint     |
| `RABBITMQ_HOST`          | `localhost`              | RabbitMQ server           |
| `OLLAMA_BASE_URL`        | `http://localhost:11434` | Ollama embedding service  |

## Development

### Run Tests

```bash
mvn test -Dtest=RunAnalysisServiceTest
mvn test -Dtest=GarminEventIntegrationTest
```

### Run with Docker

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

### View Logs

```bash
tail -f logs/runs-ai-analyzer.log
```

## Integration with runs-app

This service consumes events from runs-app when users import Garmin CSV files. The event includes:

```json
{
  "eventType": "GARMIN_CSV_RUN",
  "activityId": "12345",
  "activityName": "Morning Run",
  "activityDate": "2024-01-15T06:00:00Z",
  "distance": 5.5,
  "elapsedTime": "00:28:30",
  "databaseId": 1001,
  "status": "SUCCESS",
  "fileName": "activities.csv",
  "activityType": "running",
  "maxHeartRate": 165,
  "calories": 450
}
```

Only SUCCESS and UPDATED events are analyzed. Failed imports are skipped.

## Deployment

### Production Checklist

- Anthropic API key is configured
- PostgreSQL with pgvector extension is running
- RabbitMQ is accessible
- Spring Cloud Config server is available
- Ollama is running for embeddings
- Database migrations have run successfully

### Flyway Migrations

Migrations run automatically on startup:

1. **V001**: Create RAG tables (run_analysis_document, embeddings)
2. **V002**: Fix primary key sequences
3. **V003**: Create analysis_processing_log (idempotency tracking)
4. **V004**: Create run_journal_entry table

## Architecture Notes

- **Idempotency**: Database-backed unique constraints guarantee no duplicate analyses even if events are replayed
- **Caching**: RAG store returns cached similar analyses to avoid redundant Claude calls
- **Batching**: Events are batched for efficiency, reducing per-run analysis costs
- **Error Recovery**: Dead letter queue preserves failed messages; reconciliation retries periodically
- **Separation of Concerns**: Event listener queues work, batch processor handles analysis independently

## License

MIT
