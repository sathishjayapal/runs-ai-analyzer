# Three-Service Integration Testing Guide

## Testing eventstracker + runs-app + runs-ai-analyzer

This guide verifies that all three microservices work together correctly with the shared queue infrastructure.

---

## Prerequisites

- Java 21+
- Maven 3.8+
- Docker (for RabbitMQ)
- PostgreSQL (3 databases)
- CSV file with Garmin activities

---

## Step 1: Start Infrastructure (2 minutes)

### RabbitMQ

```bash
docker run -d --name rabbitmq \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3.13-management

# Verify
open http://localhost:15672  # guest/guest
```

### PostgreSQL Databases

```bash
# eventstracker database (port 5442)
# runs-app database (port 5443)
# runs-ai-analyzer database (port 5444)

# Verify all are running
psql -h localhost -p 5442 -U postgres -d eventstracker -c "SELECT 1"
psql -h localhost -p 5443 -U postgres -d runs-app -c "SELECT 1"
psql -h localhost -p 5444 -U postgres -d runs-ai-analyzer -c "SELECT 1"
```

---

## Step 2: Start Services in Order

### Terminal 1: eventstracker (MUST START FIRST)

```bash
cd /Users/skminfotech/IdeaProjects/eventstracker
./mvnw clean spring-boot:run
```

**Wait for:**
```
✅ "Declared queue: q.sathishprojects.garmin.api.events"
✅ "Declared queue: q.sathishprojects.garmin.ops.events"
✅ "Declared queue: dlq.sathishprojects.garmin.api.events"
✅ "Declared queue: dlq.sathishprojects.garmin.ops.events"
✅ "Started EventServiceApplication"
```

### Terminal 2: runs-app

```bash
cd /Users/skminfotech/IdeaProjects/runs-app
./mvnw clean spring-boot:run
```

**Wait for:**
```
✅ "Validated Garmin API queue exists: q.sathishprojects.garmin.api.events"
✅ "Validated Garmin OPS queue exists: q.sathishprojects.garmin.ops.events"
✅ "Started RunsAppApplication"
```

### Terminal 3: runs-ai-analyzer

```bash
cd /Users/skminfotech/IdeaProjects/runs-ai-analyzer
./mvnw clean spring-boot:run
```

**Wait for:**
```
✅ "RabbitMQ listener factory configured: prefetch=10, concurrency=2-5"
✅ "Started RunsAiAnalyzerApplication"
```

---

## Step 3: Verify Queue Setup

### RabbitMQ Management UI

```bash
open http://localhost:15672
```

**Navigate to:** Queues tab

**Verify:**

| Queue | Messages Ready | Consumers | State |
|-------|----------------|-----------|-------|
| `q.sathishprojects.garmin.api.events` | 0 | 1 (eventstracker) | Running |
| `q.sathishprojects.garmin.ops.events` | 0 | 1 (runs-ai-analyzer) | Running |
| `dlq.sathishprojects.garmin.api.events` | 0 | 0 | Idle |
| `dlq.sathishprojects.garmin.ops.events` | 0 | 0 | Idle |

**Verify Bindings:**

Click on `q.sathishprojects.garmin.api.events`:
- ✅ Bound to `x.sathishprojects.garmin.events.exchange` with pattern `sathishprojects.garmin.api.*`

Click on `q.sathishprojects.garmin.ops.events`:
- ✅ Bound to `x.sathishprojects.garmin.events.exchange` with pattern `sathishprojects.garmin.ops.*`

---

## Step 4: Import CSV File

```bash
# Copy CSV to import folder
cp ~/Downloads/activities.csv /data/garmin-fit-files/

# Or create a test CSV
cat > /data/garmin-fit-files/test.csv << 'EOF'
Activity Type,Date,Distance,Calories,Time,Avg HR,Max HR,Activity Id,Activity Name
Running,2026-04-23 08:00:00,5.5,450,00:28:30,155,165,12345678,Morning Run
Running,2026-04-23 18:00:00,3.2,280,00:18:45,150,160,12345679,Evening Run
EOF
```

---

## Step 5: Verify Event Flow

### Terminal 2 (runs-app) Logs

```
✅ "Imported CSV activity: 12345678 (DB id: 1001)"
✅ "Published SUCCESS event to API queue for CSV activity: 12345678"
✅ "Published SUCCESS event to OPS queue for CSV activity: 12345678"
✅ "Imported CSV activity: 12345679 (DB id: 1002)"
✅ "Published SUCCESS event to API queue for CSV activity: 12345679"
✅ "Published SUCCESS event to OPS queue for CSV activity: 12345679"
```

### Terminal 1 (eventstracker) Logs

```
✅ "=== Received Garmin event from RabbitMQ ==="
✅ "Event payload: {\"eventType\":\"GARMIN_CSV_RUN\",\"activityId\":\"12345678\"..."
✅ "Persisted Garmin event payload for EventId=... as DB record ID=..."
✅ "Total processed Garmin events count from queue: 1"

✅ "=== Received Garmin event from RabbitMQ ==="
✅ "Event payload: {\"eventType\":\"GARMIN_CSV_RUN\",\"activityId\":\"12345679\"..."
✅ "Persisted Garmin event payload for EventId=... as DB record ID=..."
✅ "Total processed Garmin events count from queue: 2"
```

### Terminal 3 (runs-ai-analyzer) Logs

```
✅ "Received Garmin event message: {\"eventType\":\"GARMIN_CSV_RUN\"..."
✅ "Queued Garmin run for analysis: activityId=12345678, dbId=1001"
✅ "Added event to pending queue: key=12345678_1001, queueSize=1"

✅ "Received Garmin event message: {\"eventType\":\"GARMIN_CSV_RUN\"..."
✅ "Queued Garmin run for analysis: activityId=12345679, dbId=1002"
✅ "Added event to pending queue: key=12345679_1002, queueSize=2"

(After 30 seconds)
✅ "Processing batch of 2 pending events"
✅ "Processing 2 events for batch analysis"
✅ "Fetched 2 runs from runs-app"
✅ "Batch analysis completed: runs=2, documentId=..., cached=false"
```

---

## Step 6: Verify Database State

### eventstracker Database

```sql
-- Connect
psql -h localhost -p 5442 -U postgres -d eventstracker

-- Check events stored
SELECT 
    id,
    event_type,
    event_id,
    LEFT(payload, 50) as payload_preview,
    created_at
FROM domain_event
WHERE event_type = 'GARMIN'
ORDER BY created_at DESC
LIMIT 5;
```

**Expected:** 2 rows (one for each activity)

### runs-app Database

```sql
-- Connect
psql -h localhost -p 5443 -U postgres -d runs-app

-- Check activities imported
SELECT 
    id,
    activity_id,
    activity_name,
    activity_type,
    distance,
    created_at
FROM garmin_run
ORDER BY created_at DESC
LIMIT 5;
```

**Expected:** 2 rows with activity_id = 12345678, 12345679

### runs-ai-analyzer Database

```sql
-- Connect
psql -h localhost -p 5444 -U postgres -d runs-ai-analyzer

-- Check processing logs
SELECT 
    id,
    activity_id,
    database_id,
    processing_status,
    document_id,
    created_at,
    processed_at
FROM analysis_processing_log
ORDER BY created_at DESC
LIMIT 5;
```

**Expected:** 2 rows with status = COMPLETED

```sql
-- Check analysis results
SELECT 
    document_id,
    total_runs,
    total_distance_km,
    LEFT(summary, 100) as summary_preview,
    created_at
FROM run_analysis_document
ORDER BY created_at DESC
LIMIT 5;
```

**Expected:** 1 row (batch analysis of 2 runs)

---

## Step 7: Test Idempotency

### Re-import Same CSV

```bash
# Import the same file again
cp ~/Downloads/activities.csv /data/garmin-fit-files/test2.csv
```

### Verify runs-app Logs

```
✅ "Activity already exists with same data, skipping: 2026-04-23 Morning Run"
✅ "Published SKIPPED event to API queue for CSV activity: 12345678"
(Note: NO "Published to OPS queue" - correct!)
```

### Verify eventstracker Logs

```
✅ "=== Received Garmin event from RabbitMQ ==="
✅ "Event payload: {\"status\":\"SKIPPED\"..."
✅ "Persisted Garmin event payload..."
```

### Verify runs-ai-analyzer Logs

```
(No new logs - correct! SKIPPED events not sent to OPS queue)
```

### Verify Database

```sql
-- eventstracker: Should have 4 events (2 SUCCESS + 2 SKIPPED)
SELECT status, COUNT(*) 
FROM domain_event 
WHERE event_type = 'GARMIN' 
GROUP BY status;

-- runs-ai-analyzer: Should still have 2 processing logs (no duplicates)
SELECT COUNT(*) FROM analysis_processing_log;
```

---

## Step 8: Test Error Handling

### Simulate Failed Import

```bash
# Create invalid CSV
cat > /data/garmin-fit-files/invalid.csv << 'EOF'
Activity Type,Date,Distance
Invalid,Bad Date,Not a number
EOF
```

### Verify runs-app Logs

```
✅ "Failed to save CSV activity: ..."
✅ "Published FAILED event to API queue for CSV activity: ..."
(Note: NO "Published to OPS queue" - correct!)
```

### Verify eventstracker Logs

```
✅ "=== Received Garmin event from RabbitMQ ==="
✅ "Event payload: {\"status\":\"FAILED\"..."
✅ "Persisted Garmin event payload..."
```

### Verify runs-ai-analyzer Logs

```
(No logs - correct! FAILED events not sent to OPS queue)
```

---

## Step 9: Test Reconciliation

### Stop runs-ai-analyzer

```bash
# In Terminal 3, press Ctrl+C
```

### Import New Activities

```bash
cat > /data/garmin-fit-files/new.csv << 'EOF'
Activity Type,Date,Distance,Calories,Time,Avg HR,Max HR,Activity Id,Activity Name
Running,2026-04-24 08:00:00,6.5,520,00:32:15,158,168,12345680,Long Run
EOF
```

### Verify Events Published

```
runs-app: ✅ Published to both queues
eventstracker: ✅ Received and stored
runs-ai-analyzer: ❌ Not running (events queued)
```

### Check RabbitMQ

```bash
open http://localhost:15672
```

**Verify:** `q.sathishprojects.garmin.ops.events` has 1 message ready

### Restart runs-ai-analyzer

```bash
cd /Users/skminfotech/IdeaProjects/runs-ai-analyzer
./mvnw spring-boot:run
```

### Verify Catch-Up

```
✅ "Received Garmin event message..."
✅ "Queued Garmin run for analysis: activityId=12345680"
✅ "Processing batch of 1 pending events"
✅ "Batch analysis completed: runs=1"
```

---

## Step 10: Monitor All Services

### RabbitMQ Metrics

```bash
# Check message rates
curl -u guest:guest http://localhost:15672/api/queues/%2F/q.sathishprojects.garmin.api.events | jq '.message_stats'
curl -u guest:guest http://localhost:15672/api/queues/%2F/q.sathishprojects.garmin.ops.events | jq '.message_stats'
```

### Database Metrics

```sql
-- eventstracker: Total events by status
SELECT 
    COALESCE(NULLIF(TRIM(SUBSTRING(payload FROM '"status":"([^"]*)"')), ''), 'UNKNOWN') as status,
    COUNT(*) as count
FROM domain_event 
WHERE event_type = 'GARMIN'
GROUP BY status;

-- runs-ai-analyzer: Processing status breakdown
SELECT 
    processing_status,
    COUNT(*) as count,
    ROUND(AVG(EXTRACT(EPOCH FROM (processed_at - created_at)))) as avg_processing_seconds
FROM analysis_processing_log
GROUP BY processing_status;
```

---

## Success Criteria ✅

After completing all steps, verify:

### RabbitMQ
- ✅ All 4 queues exist and are bound correctly
- ✅ 2 consumers active (eventstracker + runs-ai-analyzer)
- ✅ 0 messages in DLQs
- ✅ Message rates showing publish/deliver/ack

### eventstracker
- ✅ Receives ALL events (SUCCESS, FAILED, SKIPPED, UPDATED)
- ✅ Stores all events in `domain_event` table
- ✅ No errors in logs
- ✅ Continues working as before (no breaking changes)

### runs-app
- ✅ Publishes to both routing keys for SUCCESS/UPDATED
- ✅ Publishes to API queue only for FAILED/SKIPPED
- ✅ Validates queues exist on startup
- ✅ No errors in logs

### runs-ai-analyzer
- ✅ Receives only SUCCESS/UPDATED events
- ✅ Idempotency prevents duplicates
- ✅ Batch processing works (30s window)
- ✅ Processing logs created with correct status
- ✅ Analysis results stored in database
- ✅ Reconciliation catches up missed events

---

## Troubleshooting

### Issue: eventstracker fails to start

**Error:** `Failed to declare queue`

**Solution:** Check RabbitMQ is running and accessible

### Issue: runs-app fails to start

**Error:** `Garmin API queue not found`

**Solution:** Start eventstracker first to provision queues

### Issue: runs-ai-analyzer not receiving events

**Check:**
1. Queue has consumer: `q.sathishprojects.garmin.ops.events`
2. runs-app publishing to correct routing key: `sathishprojects.garmin.ops.event`
3. Event status is SUCCESS or UPDATED (not FAILED/SKIPPED)

### Issue: Duplicate processing in runs-ai-analyzer

**Check:**
```sql
SELECT activity_id, database_id, COUNT(*) 
FROM analysis_processing_log 
GROUP BY activity_id, database_id 
HAVING COUNT(*) > 1;
```

**Expected:** 0 rows (idempotency working)

### Issue: eventstracker not receiving events

**Check:**
1. runs-app publishing to API routing key
2. Queue binding correct: `sathishprojects.garmin.api.*`
3. eventstracker listener running

---

## Performance Testing

### Load Test (100 activities)

```bash
# Generate 100 activities
for i in {1..100}; do
  echo "Running,2026-04-24 08:00:00,$((5 + i % 10)).5,$((400 + i * 5)),00:30:00,155,165,1234567$i,Run $i"
done > /data/garmin-fit-files/load_test.csv
```

**Monitor:**
- RabbitMQ: Message rates (publish/deliver/ack)
- eventstracker: Event processing rate
- runs-ai-analyzer: Batch processing (should group into ~20 batches of 5)

**Expected:**
- All 100 events in eventstracker database
- All 100 processing logs in runs-ai-analyzer
- ~20 analysis documents (batched)
- No errors in any service
- No messages in DLQs

---

## Cleanup

```bash
# Stop services (Ctrl+C in each terminal)

# Stop RabbitMQ
docker stop rabbitmq
docker rm rabbitmq

# Clear databases (optional)
psql -h localhost -p 5442 -U postgres -d eventstracker -c "TRUNCATE domain_event CASCADE"
psql -h localhost -p 5443 -U postgres -d runs-app -c "TRUNCATE garmin_run CASCADE"
psql -h localhost -p 5444 -U postgres -d runs-ai-analyzer -c "TRUNCATE analysis_processing_log, run_analysis_document CASCADE"
```

---

## Summary

This integration test verifies:
1. ✅ eventstracker provisions queues correctly
2. ✅ runs-app publishes to both API and OPS queues
3. ✅ eventstracker receives and stores all events
4. ✅ runs-ai-analyzer receives and processes only SUCCESS/UPDATED events
5. ✅ Idempotency prevents duplicate processing
6. ✅ Error handling routes failed messages to DLQ
7. ✅ Reconciliation catches up missed events
8. ✅ All three services work together with no breaking changes

**Result:** Production-ready three-service integration! 🎉
