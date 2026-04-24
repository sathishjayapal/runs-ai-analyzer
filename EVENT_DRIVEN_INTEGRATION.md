# Event-Driven Integration: runs-app ↔ runs-ai-analyzer

## Overview

This document describes the **event-driven integration** between `runs-app` and `runs-ai-analyzer` using RabbitMQ. The integration provides:

- ✅ **Automatic AI analysis** when runs are imported
- ✅ **Idempotent processing** - no duplicate analyses
- ✅ **Error handling & retry logic** with dead letter queue
- ✅ **Reconciliation** to catch up missed events
- ✅ **Batch processing** for efficiency
- ✅ **Zero breaking changes** to existing flows

## Architecture

```
┌─────────────┐         ┌──────────────┐         ┌──────────────────┐
│  runs-app   │ Publish │   RabbitMQ   │ Consume │ runs-ai-analyzer │
│             ├────────>│   Exchange   ├────────>│                  │
│ CSV Import  │  Event  │              │  Event  │  AI Analysis     │
└─────────────┘         └──────────────┘         └──────────────────┘
                              │
                              │ Failed Messages
                              ▼
                        ┌──────────────┐
                        │  Dead Letter │
                        │    Queue     │
                        └──────────────┘
```

## Data Flow

### 1. Event Publishing (runs-app)

When a Garmin run is imported via CSV:

```java
// GarminCsvImportService.java:136
publishActivityEvent(dto, savedId, handle.getFileName());
```

**Event Structure:**
```json
{
  "eventType": "GARMIN_CSV_RUN",
  "activityId": "12345",
  "activityName": "Morning Run",
  "activityDate": "2026-04-23T08:00:00",
  "distance": "5.5",
  "elapsedTime": "00:28:30",
  "databaseId": 1001,
  "status": "SUCCESS",
  "fileName": "activities.csv",
  "activityType": "running",
  "maxHeartRate": "165",
  "calories": "450"
}
```

### 2. Event Consumption (runs-ai-analyzer)

**Listener:** `GarminEventListener.java`

```java
@RabbitListener(queues = RabbitMQListenerConfiguration.ANALYZER_QUEUE)
public void handleGarminRunEvent(@Payload String message)
```

**Processing Steps:**
1. ✅ Deserialize event
2. ✅ Check status (only process SUCCESS/UPDATED)
3. ✅ **Idempotency check** - skip if already processed
4. ✅ Create processing log (PENDING status)
5. ✅ Queue for batch analysis

### 3. Batch Processing

**Service:** `RunAnalysisBatchService.java`

- Collects events for **30 seconds** (configurable)
- Processes when:
  - Batch size reaches **5 runs** (configurable), OR
  - Oldest event is **60 minutes** old (configurable)

**Batch Flow:**
1. Fetch run data from runs-app via REST API
2. Filter for running activities
3. Call AI analysis service
4. Update processing logs with results

### 4. Reconciliation

**Service:** `ReconciliationService.java`

Runs every **6 hours** (configurable) to:

#### a) Retry Failed Events
- Finds events with status=FAILED
- Retries up to **3 times** (configurable)
- Waits **30 minutes** between retries (configurable)

#### b) Catch Up Missed Runs
- Queries runs-app for recent runs (last **7 days**)
- Compares with processing log
- Analyzes any missing runs

#### c) Cleanup
- Removes old processing logs

## Configuration

### runs-ai-analyzer (application.yaml)

```yaml
runs-app:
  base-url: http://localhost:8080

analysis:
  batch:
    size: 5                    # Minimum runs to trigger analysis
    window-minutes: 60         # Max wait time before processing
    interval-ms: 30000         # Batch collection interval (30s)

reconciliation:
  enabled: true
  max-retries: 3               # Max retry attempts for failed events
  retry-delay-minutes: 30      # Wait time between retries
  lookback-days: 7             # How far back to check for missed runs
  cron: "0 0 */6 * * *"        # Every 6 hours

rabbitmq:
  listener:
    prefetch: 10               # Messages to prefetch
    concurrency: 2             # Min concurrent consumers
    max-concurrency: 5         # Max concurrent consumers
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `RUNS_APP_BASE_URL` | `http://localhost:8080` | runs-app API endpoint |
| `ANALYSIS_BATCH_SIZE` | `5` | Minimum runs per batch |
| `RECONCILIATION_ENABLED` | `true` | Enable reconciliation |
| `RABBITMQ_LISTENER_PREFETCH` | `10` | RabbitMQ prefetch count |

## Database Schema

### analysis_processing_log

Tracks processing status for idempotency and reconciliation:

```sql
CREATE TABLE analysis_processing_log (
    id BIGSERIAL PRIMARY KEY,
    activity_id VARCHAR(255) NOT NULL,
    database_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    processing_status VARCHAR(50) NOT NULL,  -- PENDING, PROCESSING, COMPLETED, FAILED, SKIPPED
    document_id VARCHAR(255),                 -- UUID of analysis result
    retry_count INTEGER DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    last_retry_at TIMESTAMP,
    CONSTRAINT uk_activity_database UNIQUE (activity_id, database_id)
);
```

## Idempotency Guarantee

**Mechanism:**
- Unique constraint on `(activity_id, database_id)`
- Check before processing: if log exists with status COMPLETED or PROCESSING, skip

**Example:**
```java
private boolean isAlreadyProcessed(GarminRunEvent event) {
    return processingLogRepository
        .findByActivityIdAndDatabaseId(event.getActivityId(), event.getDatabaseId())
        .filter(log -> log.getProcessingStatus() == COMPLETED 
                    || log.getProcessingStatus() == PROCESSING)
        .isPresent();
}
```

## Error Handling

### Dead Letter Queue (DLQ)

Failed messages are routed to DLQ after all retries exhausted:

- **Exchange:** `x.runs.ai.analyzer.dlx`
- **Queue:** `q.runs.ai.analyzer.dlq`
- **Routing Key:** `analyzer.dlq`

### Retry Strategy

1. **Immediate retry** (RabbitMQ listener)
2. **Delayed retry** (Reconciliation service)
   - 30 min, 60 min, 90 min (configurable)
3. **Manual intervention** (DLQ inspection)

## Testing

### Integration Test

```bash
cd runs-ai-analyzer
mvn test -Dtest=GarminEventIntegrationTest
```

**Test Coverage:**
- ✅ Event processing creates log
- ✅ Idempotency prevents duplicates
- ✅ Non-SUCCESS events are skipped

### Manual Testing

#### 1. Start Infrastructure

```bash
# Terminal 1: Start RabbitMQ
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3.13-management

# Terminal 2: Start runs-app
cd runs-app
./mvnw spring-boot:run

# Terminal 3: Start runs-ai-analyzer
cd runs-ai-analyzer
./mvnw spring-boot:run
```

#### 2. Import CSV File

```bash
# Place CSV in runs-app import folder
cp activities.csv /data/garmin-fit-files/
```

#### 3. Verify Processing

```bash
# Check RabbitMQ
open http://localhost:15672  # guest/guest

# Check processing logs
psql -h localhost -p 5444 -U postgres -d runs-ai-analyzer
SELECT * FROM analysis_processing_log ORDER BY created_at DESC LIMIT 10;

# Check analysis results
SELECT * FROM run_analysis_document ORDER BY created_at DESC LIMIT 5;
```

## Monitoring

### Key Metrics

```sql
-- Processing status breakdown
SELECT processing_status, COUNT(*) 
FROM analysis_processing_log 
GROUP BY processing_status;

-- Failed events needing attention
SELECT * FROM analysis_processing_log 
WHERE processing_status = 'FAILED' 
  AND retry_count >= 3
ORDER BY created_at DESC;

-- Recent successful analyses
SELECT * FROM analysis_processing_log 
WHERE processing_status = 'COMPLETED' 
  AND processed_at > NOW() - INTERVAL '24 hours'
ORDER BY processed_at DESC;
```

### RabbitMQ Management

```bash
# View queue stats
curl -u guest:guest http://localhost:15672/api/queues/%2F/q.runs.ai.analyzer.garmin.events

# View DLQ messages
curl -u guest:guest http://localhost:15672/api/queues/%2F/q.runs.ai.analyzer.dlq
```

## Troubleshooting

### Issue: Events not being consumed

**Check:**
1. RabbitMQ connection: `spring.rabbitmq.host/port`
2. Queue exists: `q.runs.ai.analyzer.garmin.events`
3. Listener enabled: Check logs for "RabbitMQ listener factory configured"

**Fix:**
```bash
# Restart runs-ai-analyzer
./mvnw spring-boot:run
```

### Issue: Duplicate analyses

**Check:**
```sql
SELECT activity_id, database_id, COUNT(*) 
FROM analysis_processing_log 
GROUP BY activity_id, database_id 
HAVING COUNT(*) > 1;
```

**Fix:** Unique constraint prevents this, but if found:
```sql
DELETE FROM analysis_processing_log 
WHERE id NOT IN (
    SELECT MIN(id) 
    FROM analysis_processing_log 
    GROUP BY activity_id, database_id
);
```

### Issue: Reconciliation not running

**Check:**
```yaml
reconciliation:
  enabled: true  # Must be true
```

**Verify:**
```bash
# Check logs for "Starting reconciliation process"
tail -f logs/runs-ai-analyzer.log | grep reconciliation
```

## Performance Tuning

### High Volume (1000+ runs/day)

```yaml
analysis:
  batch:
    size: 10              # Larger batches
    interval-ms: 60000    # 1 minute collection window

rabbitmq:
  listener:
    prefetch: 20
    concurrency: 5
    max-concurrency: 10
```

### Low Volume (<100 runs/day)

```yaml
analysis:
  batch:
    size: 3               # Smaller batches
    window-minutes: 30    # Process sooner
    interval-ms: 15000    # 15 second collection

rabbitmq:
  listener:
    prefetch: 5
    concurrency: 1
    max-concurrency: 2
```

## Migration Guide

### Existing Deployments

1. **Deploy runs-ai-analyzer** with new code
2. **Run Flyway migration** (V003__CREATE_PROCESSING_LOG_TABLE.sql)
3. **Deploy runs-app** with batch endpoints
4. **Verify** RabbitMQ queues created
5. **Enable reconciliation** to catch up historical data

### Rollback Plan

If issues occur:

1. Set `reconciliation.enabled=false`
2. Stop runs-ai-analyzer
3. runs-app continues working normally (no breaking changes)
4. Investigate and fix
5. Restart runs-ai-analyzer

## Best Practices

1. ✅ **Monitor DLQ** - Set up alerts for messages in DLQ
2. ✅ **Tune batch size** - Based on your import volume
3. ✅ **Review failed logs** - Weekly check of FAILED status
4. ✅ **Test idempotency** - Replay events to verify no duplicates
5. ✅ **Backup processing logs** - Before major changes

## Support

For issues or questions:
- Check logs: `logs/runs-ai-analyzer.log`
- Review processing logs: `SELECT * FROM analysis_processing_log WHERE processing_status = 'FAILED'`
- Inspect DLQ: RabbitMQ Management UI → Queues → q.runs.ai.analyzer.dlq
