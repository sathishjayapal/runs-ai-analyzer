# Integration with eventstracker Queue Infrastructure

## Overview

The `runs-ai-analyzer` service now integrates with the **existing eventstracker queue infrastructure** instead of creating its own queues. This ensures consistency across all three microservices: `runs-app`, `eventstracker`, and `runs-ai-analyzer`.

---

## Architecture

```
┌─────────────┐                    ┌──────────────────┐
│  runs-app   │                    │  eventstracker   │
│             │                    │  (provisions     │
│ CSV Import  │                    │   queues)        │
└──────┬──────┘                    └────────┬─────────┘
       │                                    │
       │ Publishes to 2 routing keys       │ Provisions queues
       │                                    │ & exchanges
       ▼                                    ▼
┌──────────────────────────────────────────────────────┐
│         x.sathishprojects.garmin.events.exchange     │
│                   (Topic Exchange)                    │
└──────────────────┬───────────────────┬───────────────┘
                   │                   │
    API routing    │                   │  OPS routing
    key pattern    │                   │  key pattern
    *.api.*        │                   │  *.ops.*
                   ▼                   ▼
         ┌─────────────────┐  ┌─────────────────┐
         │ GARMIN_API_      │  │ GARMIN_OPS_     │
         │ EVENTS_QUEUE     │  │ EVENTS_QUEUE    │
         └────────┬─────────┘  └────────┬────────┘
                  │                     │
                  ▼                     ▼
         ┌─────────────────┐  ┌─────────────────┐
         │ eventstracker   │  │ runs-ai-        │
         │ (audit/logging) │  │ analyzer        │
         │                 │  │ (AI analysis)   │
         └─────────────────┘  └─────────────────┘
```

---

## Queue Infrastructure (Provisioned by eventstracker)

### Exchanges

| Exchange | Type | Purpose |
|----------|------|---------|
| `x.sathishprojects.garmin.events.exchange` | Topic | Main exchange for Garmin events |
| `x.sathishprojects.garmin.events.dlx.exchange` | Topic | Dead letter exchange for failed messages |

### Queues

| Queue | Consumer | Purpose | DLQ |
|-------|----------|---------|-----|
| `q.sathishprojects.garmin.api.events` | eventstracker | Audit/logging of all events | `dlq.sathishprojects.garmin.api.events` |
| `q.sathishprojects.garmin.ops.events` | runs-ai-analyzer | AI analysis of running activities | `dlq.sathishprojects.garmin.ops.events` |

### Routing Keys

| Routing Key | Queue Binding | Publisher | Purpose |
|-------------|---------------|-----------|---------|
| `sathishprojects.garmin.api.event` | GARMIN_API_EVENTS_QUEUE | runs-app | All events (SUCCESS, FAILED, SKIPPED, UPDATED) |
| `sathishprojects.garmin.ops.event` | GARMIN_OPS_EVENTS_QUEUE | runs-app | Only SUCCESS and UPDATED events |
| `sathishprojects.garmin.ops.analysis` | GARMIN_OPS_EVENTS_QUEUE | runs-ai-analyzer | Analysis results |

---

## Service Responsibilities

### 1. eventstracker

**Role:** Infrastructure provisioning + audit/logging

**Responsibilities:**
- ✅ Provisions all queues and exchanges on startup
- ✅ Consumes from `GARMIN_API_EVENTS_QUEUE`
- ✅ Stores all events in `domain_event` table for audit
- ✅ Provides event replay capability

**Configuration:** `RabbitSchemaConfig.java`

```java
public static final String GARMIN_API_EVENTS_QUEUE = "q.sathishprojects.garmin.api.events";
public static final String GARMIN_OPS_EVENTS_QUEUE = "q.sathishprojects.garmin.ops.events";
public static final String DLQ_GARMIN_API_EVENTS_QUEUE = "dlq.sathishprojects.garmin.api.events";
public static final String DLQ_GARMIN_OPS_EVENTS_QUEUE = "dlq.sathishprojects.garmin.ops.events";
```

### 2. runs-app

**Role:** Event publisher

**Responsibilities:**
- ✅ Publishes events to **both** routing keys:
  - `sathishprojects.garmin.api.event` → eventstracker (all events)
  - `sathishprojects.garmin.ops.event` → runs-ai-analyzer (SUCCESS/UPDATED only)
- ✅ Validates queues exist on startup
- ✅ Includes full event data (activityType, maxHeartRate, calories)

**Publishing Logic:**

```java
// SUCCESS and UPDATED events → both queues
rabbitTemplate.convertAndSend(GARMIN_EXCHANGE, GARMIN_API_ROUTING_KEY, event); // eventstracker
rabbitTemplate.convertAndSend(GARMIN_EXCHANGE, GARMIN_OPS_ROUTING_KEY, event); // runs-ai-analyzer

// FAILED and SKIPPED events → API queue only
rabbitTemplate.convertAndSend(GARMIN_EXCHANGE, GARMIN_API_ROUTING_KEY, event); // eventstracker only
```

### 3. runs-ai-analyzer

**Role:** Event consumer + AI analysis

**Responsibilities:**
- ✅ Consumes from `GARMIN_OPS_EVENTS_QUEUE`
- ✅ Idempotent processing (no duplicates)
- ✅ Batch processing (5 runs per batch)
- ✅ Error handling with DLQ
- ✅ Reconciliation to catch up missed events
- ✅ Publishes analysis results back to `GARMIN_OPS_EVENTS_QUEUE`

**Configuration:** `RabbitMQListenerConfiguration.java`

```java
public static final String ANALYZER_QUEUE = "q.sathishprojects.garmin.ops.events";
public static final String DLQ_QUEUE = "dlq.sathishprojects.garmin.ops.events";
```

---

## Event Flow

### Scenario 1: Successful CSV Import

```
1. runs-app imports CSV activity
   ↓
2. runs-app publishes GarminRunEvent with status=SUCCESS
   ├─→ sathishprojects.garmin.api.event (eventstracker)
   └─→ sathishprojects.garmin.ops.event (runs-ai-analyzer)
   ↓
3. eventstracker receives event
   ├─→ Stores in domain_event table
   └─→ Logs: "Persisted Garmin event payload"
   ↓
4. runs-ai-analyzer receives event
   ├─→ Idempotency check
   ├─→ Creates processing log (PENDING)
   ├─→ Queues for batch analysis
   └─→ After 30s, processes batch
   ↓
5. runs-ai-analyzer publishes analysis result
   └─→ sathishprojects.garmin.ops.analysis (eventstracker can consume if needed)
```

### Scenario 2: Failed CSV Import

```
1. runs-app fails to import CSV activity
   ↓
2. runs-app publishes GarminRunEvent with status=FAILED
   └─→ sathishprojects.garmin.api.event (eventstracker ONLY)
   ↓
3. eventstracker receives event
   ├─→ Stores in domain_event table
   └─→ Logs: "Persisted Garmin event payload"
   ↓
4. runs-ai-analyzer does NOT receive event (correct behavior)
```

### Scenario 3: Updated Activity

```
1. runs-app detects data change in CSV
   ↓
2. runs-app updates activity and publishes with status=UPDATED
   ├─→ sathishprojects.garmin.api.event (eventstracker)
   └─→ sathishprojects.garmin.ops.event (runs-ai-analyzer)
   ↓
3. Both services process as per Scenario 1
```

---

## Startup Order

**CRITICAL:** Services must start in this order:

1. **eventstracker** (provisions queues)
2. **runs-app** (validates queues exist)
3. **runs-ai-analyzer** (consumes from queues)

### Validation

**runs-app** validates queues on startup:

```java
@Bean
public ApplicationRunner garminQueueValidator(AmqpAdmin amqpAdmin) {
    // Throws exception if queues don't exist
    amqpAdmin.getQueueProperties(GARMIN_API_QUEUE);
    amqpAdmin.getQueueProperties(GARMIN_OPS_QUEUE);
}
```

---

## Configuration

### eventstracker (application.yml)

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
```

### runs-app (application.yml)

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
```

### runs-ai-analyzer (application.yaml)

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

rabbitmq:
  listener:
    prefetch: 10
    concurrency: 2
    max-concurrency: 5
```

---

## Testing

### 1. Verify Queue Provisioning

```bash
# Start eventstracker first
cd eventstracker && ./mvnw spring-boot:run

# Check logs for:
# "Declared queue: q.sathishprojects.garmin.api.events"
# "Declared queue: q.sathishprojects.garmin.ops.events"
```

### 2. Verify runs-app Publishing

```bash
# Start runs-app
cd runs-app && ./mvnw spring-boot:run

# Check logs for:
# "Validated Garmin API queue exists: q.sathishprojects.garmin.api.events"
# "Validated Garmin OPS queue exists: q.sathishprojects.garmin.ops.events"

# Import CSV
cp activities.csv /data/garmin-fit-files/

# Check logs for:
# "Published SUCCESS event to API queue for CSV activity: 12345"
# "Published SUCCESS event to OPS queue for CSV activity: 12345"
```

### 3. Verify runs-ai-analyzer Consumption

```bash
# Start runs-ai-analyzer
cd runs-ai-analyzer && ./mvnw spring-boot:run

# Check logs for:
# "RabbitMQ listener factory configured"
# "Received Garmin event message"
# "Queued Garmin run for analysis: activityId=12345"
```

### 4. Verify eventstracker Consumption

```bash
# Check eventstracker logs for:
# "=== Received Garmin event from RabbitMQ ==="
# "Persisted Garmin event payload for EventId=..."
```

---

## Monitoring

### RabbitMQ Management UI

```bash
open http://localhost:15672  # guest/guest
```

**Check:**
- ✅ Exchange `x.sathishprojects.garmin.events.exchange` exists
- ✅ Queue `q.sathishprojects.garmin.api.events` has 1 consumer (eventstracker)
- ✅ Queue `q.sathishprojects.garmin.ops.events` has 1 consumer (runs-ai-analyzer)
- ✅ Message flow: Published → Delivered → Acknowledged

### Database Verification

**eventstracker:**
```sql
-- Check events stored
SELECT * FROM domain_event 
WHERE event_type = 'GARMIN' 
ORDER BY created_at DESC 
LIMIT 10;
```

**runs-ai-analyzer:**
```sql
-- Check processing logs
SELECT * FROM analysis_processing_log 
ORDER BY created_at DESC 
LIMIT 10;
```

---

## Troubleshooting

### Issue: runs-app fails to start

**Error:** `Garmin API queue 'q.sathishprojects.garmin.api.events' not found`

**Solution:** Start eventstracker first to provision queues

### Issue: runs-ai-analyzer not receiving events

**Check:**
1. Queue exists: `q.sathishprojects.garmin.ops.events`
2. Binding exists: `sathishprojects.garmin.ops.*` → queue
3. runs-app publishing to correct routing key: `sathishprojects.garmin.ops.event`

**Verify:**
```bash
curl -u guest:guest http://localhost:15672/api/queues/%2F/q.sathishprojects.garmin.ops.events
```

### Issue: Duplicate events in eventstracker

**Expected behavior:** eventstracker receives ALL events (SUCCESS, FAILED, SKIPPED, UPDATED)

**Verify:**
```sql
SELECT status, COUNT(*) 
FROM domain_event 
WHERE event_type = 'GARMIN' 
GROUP BY status;
```

### Issue: runs-ai-analyzer processing FAILED events

**Check:** runs-ai-analyzer should skip non-SUCCESS/UPDATED events

**Verify:**
```sql
SELECT processing_status, COUNT(*) 
FROM analysis_processing_log 
GROUP BY processing_status;
```

---

## Benefits of This Architecture

1. ✅ **Centralized queue management** - eventstracker owns infrastructure
2. ✅ **Separation of concerns** - API queue for audit, OPS queue for operations
3. ✅ **No breaking changes** - eventstracker continues working as before
4. ✅ **Scalability** - Multiple consumers can subscribe to OPS queue
5. ✅ **Audit trail** - All events logged in eventstracker
6. ✅ **Selective processing** - runs-ai-analyzer only processes relevant events
7. ✅ **DLQ per queue** - Failed messages isolated by consumer

---

## Migration from Previous Implementation

If you previously had custom queues in runs-ai-analyzer:

1. ✅ **Removed:** Custom queue creation (`q.runs.ai.analyzer.garmin.events`)
2. ✅ **Removed:** Custom DLQ (`q.runs.ai.analyzer.dlq`)
3. ✅ **Updated:** Listener to use `q.sathishprojects.garmin.ops.events`
4. ✅ **Updated:** runs-app to publish to both routing keys
5. ✅ **Preserved:** All error handling, idempotency, reconciliation logic

**No data loss:** All existing functionality maintained, just using shared infrastructure.

---

## Summary

- **eventstracker** provisions queues and consumes API events for audit
- **runs-app** publishes to both API and OPS routing keys
- **runs-ai-analyzer** consumes OPS events for AI analysis
- All three services work together seamlessly with no breaking changes
