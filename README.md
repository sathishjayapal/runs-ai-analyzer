# Runs AI Analyzer

A Spring Boot microservice that analyzes Garmin running data using Anthropic Claude to provide AI-powered insights and recommendations.

## Overview

This microservice complements the `runs-app` project by providing intelligent analysis of running activities. It accepts Garmin run data, determines if the data contains running activities, and uses Anthropic Claude to generate personalized training insights.

## Features

- **Run Detection**: Identifies running activities from mixed Garmin data
- **AI Analysis**: Leverages Anthropic Claude for intelligent performance analysis
- **Performance Metrics**: Calculates total distance, duration, pace, and more
- **Training Insights**: Provides actionable recommendations for improvement
- **RESTful API**: Clean API with OpenAPI/Swagger documentation

## Tech Stack

- **Java 21**
- **Spring Boot 4.0.1**
- **Spring AI** (Anthropic Claude integration)
- **SpringDoc OpenAPI 3.0**
- **Lombok**

## Prerequisites

- JDK 21+
- Maven 3.9+
- Anthropic API Key

## Configuration

Set your Anthropic API key as an environment variable:

```bash
export ANTHROPIC_API_KEY=your-api-key-here
```

Or configure in `application.yaml`:

```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:your-api-key-here}
```

## Running the Application

```bash
cd runs-ai-analyzer
./mvnw spring-boot:run
```

The service starts on port **8081** by default.

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/analysis/analyze` | Analyze multiple Garmin run activities |
| POST | `/api/v1/analysis/check` | Check if data contains running activities |
| POST | `/api/v1/analysis/analyze/single` | Analyze a single run activity |

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
    ]
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
  "analyzedAt": "2024-01-15T10:30:00Z"
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
