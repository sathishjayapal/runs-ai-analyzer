# Runs AI Analyzer

A Spring Boot microservice that analyzes Garmin running data using Anthropic Claude to provide AI-powered insights and recommendations, with RAG-based caching and semantic search of past analyses.

## Overview

This microservice complements the `runs-app` project by providing intelligent analysis of running activities. It accepts Garmin run data, determines if the data contains running activities, and uses Anthropic Claude to generate personalized training insights. Analyses are stored in a PgVector-backed vector store for semantic search and RAG-based caching to avoid redundant LLM calls.

## Features

- **Run Detection**: Identifies running activities from mixed Garmin data
- **AI Analysis**: Leverages Anthropic Claude (Sonnet 4.5) for intelligent performance analysis
- **Performance Metrics**: Calculates total distance, duration, pace, and more
- **Training Insights**: Provides actionable recommendations for improvement
- **RAG Caching**: Stores analyses in PgVector and returns cached results for similar queries (configurable similarity threshold)
- **Semantic Search**: Search past analyses by natural language queries
- **Force Refresh**: Bypass RAG cache to get fresh LLM analysis on demand
- **RESTful API**: Clean API with OpenAPI/Swagger documentation

## Tech Stack

- **Java 21**
- **Spring Boot 4.0.1**
- **Spring AI 2.0.0-M1** (Anthropic Claude + Ollama)
- **PostgreSQL** with **PgVector** extension (vector store)
- **Ollama** (nomic-embed-text model for embeddings)
- **Flyway** (database migrations)
- **SpringDoc OpenAPI 3.0**
- **Lombok**
- **Testcontainers** (integration tests)

## Prerequisites

- JDK 21+
- Maven 3.9+
- Docker (for PostgreSQL/PgVector and Ollama via Docker Compose)
- Anthropic API Key

## Getting Started

Start the required infrastructure services:

```bash
cd runs-ai-analyzer
docker compose up -d
```

This starts:
- **PostgreSQL 17** with PgVector on port `5444`
- **Ollama** on port `11434` (auto-pulls the `nomic-embed-text` embedding model)

## Configuration

Set your Anthropic API key as an environment variable:

```bash
export ANTHROPIC_API_KEY=your-api-key-here
```

Key configuration in `application.yaml`:

| Property | Default | Description |
|----------|---------|-------------|
| `ANTHROPIC_API_KEY` | - | Anthropic API key (required) |
| `RAG_RUNS_AI_JDBC_DATABASE_URL` | `jdbc:postgresql://localhost:5444/runs-ai-analyzer` | PostgreSQL connection URL |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |
| `rag.cache.enabled` | `true` | Enable RAG-based caching |
| `rag.cache.similarity-threshold` | `0.85` | Similarity threshold for cache hits |
| `rag.cache.ttl-days` | `7` | Cache TTL in days |

## Running the Application

```bash
cd runs-ai-analyzer
./mvnw spring-boot:run
```

The service starts on port **8081** by default.

## API Endpoints

### Run Analysis

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/analysis/analyze` | Analyze multiple Garmin run activities |
| POST | `/api/v1/analysis/check` | Check if data contains running activities |
| POST | `/api/v1/analysis/analyze/single` | Analyze a single run activity |

### RAG Search

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/rag/search` | Search similar past analyses using semantic similarity |
| GET | `/api/v1/rag/recent?limit=10` | Get most recent analyses |
| GET | `/api/v1/rag/document/{documentId}` | Get a specific analysis by document ID |
| GET | `/api/v1/rag/activity/{activityId}` | Find analyses containing a specific activity |
| GET | `/api/v1/rag/distance?minDistanceKm=5.0` | Find analyses by minimum distance |

### API Documentation

Swagger UI: http://localhost:8081/swagger-ui.html
OpenAPI Spec: http://localhost:8081/api-docs

## Example Request

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
        "distance": "5.5",
        "elapsedTime": "00:28:30",
        "maxHeartRate": "165",
        "calories": "450"
      }
    ],
    "forceRefresh": false
  }'
```

## Example Response

```json
{
  "containsRunData": true,
  "summary": "Analysis of 1 running activities covering 5.50 km in 00:28:30. Average pace: 5.18 min/km.",
  "insights": [
    {
      "category": "Volume",
      "observation": "Analyzed 1 running activities",
      "recommendation": "Consistent training is key to improvement"
    }
  ],
  "metrics": {
    "totalRuns": 1,
    "totalDistanceKm": 5.5,
    "totalDuration": "00:28:30",
    "averagePaceMinPerKm": 5.18,
    "averageHeartRate": 165,
    "totalCalories": 450
  },
  "rawAnalysis": "...(AI-generated analysis)...",
  "analyzedAt": "2024-01-15T10:30:00Z",
  "cachedResult": false
}
```

## Integration with runs-app

This microservice is designed to receive Garmin run data from the `runs-app`. The data format matches the `GarminRunDTO` structure used in the main application.

## Health Check

```bash
curl http://localhost:8081/actuator/health
```

## License

MIT
