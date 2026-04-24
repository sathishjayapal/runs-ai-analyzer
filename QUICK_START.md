# Quick Start Guide - Event-Driven Integration

## 🚀 Get Running in 5 Minutes

### Prerequisites

- Java 21+
- Maven 3.8+
- Docker & Docker Compose
- PostgreSQL client (psql)

---

## Step 1: Start Infrastructure (2 minutes)

```bash
# Terminal 1: Start RabbitMQ
docker run -d --name rabbitmq \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3.13-management

# Verify RabbitMQ is running
open http://localhost:15672  # Login: guest/guest
```

---

## Step 2: Start runs-app (1 minute)

```bash
# Terminal 2
cd /Users/skminfotech/IdeaProjects/runs-app
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

**Wait for:** `Started RunsAppApplication`

---

## Step 3: Start runs-ai-analyzer (1 minute)

```bash
# Terminal 3
cd /Users/skminfotech/IdeaProjects/runs-ai-analyzer
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

**Wait for:**
- `RabbitMQ listener factory configured`
- `Started RunsAiAnalyzerApplication`

---

## Step 4: Verify Integration (1 minute)

### Check RabbitMQ Queues

Open http://localhost:15672 → Queues tab

You should see:
- ✅ `q.runs.ai.analyzer.garmin.events` (0 messages)
- ✅ `q.runs.ai.analyzer.dlq` (0 messages)
- ✅ `q.sathishprojects.garmin.api.events` (existing queue)

### Check Database Tables

```bash
# Connect to runs-ai-analyzer database
psql -h localhost -p 5444 -U postgres -d runs-ai-analyzer

# Verify table exists
\dt analysis_processing_log

# Should show empty table
SELECT COUNT(*) FROM analysis_processing_log;
```

---

## Step 5: Test the Flow (30 seconds)

### Option A: Import CSV File

```bash
# Copy a CSV file to the import folder
cp ~/Downloads/activities.csv /data/garmin-fit-files/

# Watch runs-app logs for:
# "Imported CSV activity: 12345 (DB id: 1001)"
# "Published SUCCESS event for CSV activity: 12345"

# Watch runs-ai-analyzer logs for:
# "Received Garmin event message"
# "Queued Garmin run for analysis: activityId=12345, dbId=1001"
```

### Option B: Manual Event (Testing)

```bash
# Send test event via RabbitMQ Management UI
# http://localhost:15672 → Queues → q.runs.ai.analyzer.garmin.events → Publish message

# Payload:
{
  "eventType": "GARMIN_CSV_RUN",
  "activityId": "test-123",
  "activityName": "Test Run",
  "activityDate": "2026-04-23T08:00:00",
  "distance": "5.5",
  "elapsedTime": "00:28:30",
  "databaseId": 9999,
  "status": "SUCCESS",
  "fileName": "test.csv",
  "activityType": "running",
  "maxHeartRate": "165",
  "calories": "450"
}
```

---

## Step 6: Verify Processing

### Check Processing Log

```sql
-- Connect to database
psql -h localhost -p 5444 -U postgres -d runs-ai-analyzer

-- View processing logs
SELECT 
    activity_id,
    database_id,
    processing_status,
    created_at,
    processed_at
FROM analysis_processing_log
ORDER BY created_at DESC
LIMIT 5;
```

**Expected Output:**
```
 activity_id | database_id | processing_status |      created_at      |     processed_at     
-------------+-------------+-------------------+----------------------+----------------------
 test-123    |        9999 | PENDING           | 2026-04-23 08:30:00  | 
```

### Check Batch Processing (Wait 30 seconds)

After the batch interval (30s), check again:

```sql
SELECT 
    activity_id,
    processing_status,
    document_id,
    error_message
FROM analysis_processing_log
ORDER BY created_at DESC;
```

**Expected:** Status changes to `PROCESSING` or `COMPLETED`

---

## Troubleshooting

### Issue: No events received

**Check:**
```bash
# Verify RabbitMQ connection
curl -u guest:guest http://localhost:15672/api/connections

# Check runs-ai-analyzer logs
tail -f logs/runs-ai-analyzer.log | grep -i rabbit
```

**Fix:**
- Ensure RabbitMQ is running on port 5672
- Check `spring.rabbitmq.host=localhost` in application.yaml

### Issue: Events stuck in PENDING

**Check:**
```sql
SELECT * FROM analysis_processing_log 
WHERE processing_status = 'PENDING' 
  AND created_at < NOW() - INTERVAL '5 minutes';
```

**Fix:**
- Check batch processor logs: `grep "Processing batch" logs/runs-ai-analyzer.log`
- Verify runs-app is accessible: `curl http://localhost:8080/actuator/health`

### Issue: Events failing

**Check:**
```sql
SELECT activity_id, error_message 
FROM analysis_processing_log 
WHERE processing_status = 'FAILED';
```

**Common Causes:**
- runs-app not running → `error_message: "Connection refused"`
- Invalid data → `error_message: "No running activities"`
- AI service down → `error_message: "Unable to generate run analysis"`

---

## Monitoring Dashboard (SQL)

```sql
-- Overall health
SELECT 
    processing_status,
    COUNT(*) as count,
    ROUND(AVG(EXTRACT(EPOCH FROM (processed_at - created_at)))) as avg_seconds
FROM analysis_processing_log
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY processing_status;

-- Recent activity
SELECT 
    activity_id,
    database_id,
    processing_status,
    EXTRACT(EPOCH FROM (processed_at - created_at)) as processing_time_seconds,
    created_at
FROM analysis_processing_log
ORDER BY created_at DESC
LIMIT 10;

-- Failed events
SELECT 
    activity_id,
    retry_count,
    error_message,
    last_retry_at
FROM analysis_processing_log
WHERE processing_status = 'FAILED'
ORDER BY created_at DESC;
```

---

## Next Steps

### 1. Test Idempotency

Send the same event twice:

```bash
# Send event #1
# (use RabbitMQ Management UI or import same CSV)

# Send event #2 (duplicate)
# (send same event again)

# Verify only ONE log entry exists
SELECT COUNT(*) FROM analysis_processing_log 
WHERE activity_id = 'test-123' AND database_id = 9999;
-- Expected: 1
```

### 2. Test Reconciliation

```bash
# Manually trigger reconciliation
curl -X POST http://localhost:8081/actuator/scheduledtasks

# Or wait for scheduled run (every 6 hours)
# Check logs for: "Starting reconciliation process"
```

### 3. Test Error Recovery

```bash
# Stop runs-app (simulate failure)
# Send event → should fail
# Check processing log → status = FAILED

# Restart runs-app
# Wait for reconciliation (or trigger manually)
# Check processing log → status = COMPLETED
```

### 4. Load Testing

```bash
# Import multiple CSV files
for i in {1..10}; do
  cp activities.csv /data/garmin-fit-files/activities_$i.csv
  sleep 2
done

# Monitor processing
watch -n 1 'psql -h localhost -p 5444 -U postgres -d runs-ai-analyzer -c "SELECT processing_status, COUNT(*) FROM analysis_processing_log GROUP BY processing_status"'
```

---

## Configuration Tuning

### High Volume (Fast Processing)

```yaml
# application.yaml
analysis:
  batch:
    size: 3              # Process smaller batches
    interval-ms: 10000   # Check every 10 seconds

rabbitmq:
  listener:
    prefetch: 20
    max-concurrency: 10
```

### Low Volume (Cost Optimization)

```yaml
# application.yaml
analysis:
  batch:
    size: 10             # Wait for larger batches
    window-minutes: 120  # Max 2 hour wait

rabbitmq:
  listener:
    prefetch: 5
    max-concurrency: 2
```

---

## Success Indicators ✅

After following this guide, you should see:

1. ✅ RabbitMQ queues created and bound
2. ✅ Events flowing from runs-app to runs-ai-analyzer
3. ✅ Processing logs created with PENDING status
4. ✅ Batch processor running every 30 seconds
5. ✅ Status changing to COMPLETED after processing
6. ✅ Analysis results stored in `run_analysis_document`
7. ✅ Idempotency preventing duplicates
8. ✅ Reconciliation catching up missed events

---

## Support

**Logs:**
- runs-app: `logs/runs-app.log`
- runs-ai-analyzer: `logs/runs-ai-analyzer.log`

**Database:**
```bash
# runs-app
psql -h localhost -p 5443 -U postgres -d runs-app

# runs-ai-analyzer
psql -h localhost -p 5444 -U postgres -d runs-ai-analyzer
```

**RabbitMQ:**
- Management UI: http://localhost:15672
- Credentials: guest/guest

**Health Checks:**
- runs-app: http://localhost:8080/actuator/health
- runs-ai-analyzer: http://localhost:8081/actuator/health

---

## 🎉 You're Ready!

The event-driven integration is now running. Every time a CSV is imported in runs-app, it will automatically trigger AI analysis in runs-ai-analyzer with full error handling, retry logic, and reconciliation.

**Happy analyzing! 🏃‍♂️📊**
