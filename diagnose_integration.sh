#!/bin/bash

echo "🔍 Diagnosing Three-Service Integration..."
echo ""

# Check services
echo "1️⃣ Service Health Checks:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

EVENTSTRACKER_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/actuator/health)
if [ "$EVENTSTRACKER_STATUS" = "200" ]; then
    echo "✅ eventstracker: Running (port 8082)"
else
    echo "❌ eventstracker: NOT running (port 8082) - START THIS FIRST!"
fi

RUNS_APP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health)
if [ "$RUNS_APP_STATUS" = "200" ] || [ "$RUNS_APP_STATUS" = "401" ]; then
    echo "✅ runs-app: Running (port 8080)"
else
    echo "❌ runs-app: NOT running (port 8080)"
fi

ANALYZER_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/actuator/health)
if [ "$ANALYZER_STATUS" = "200" ]; then
    echo "✅ runs-ai-analyzer: Running (port 8081)"
else
    echo "❌ runs-ai-analyzer: NOT running (port 8081)"
fi

echo ""
echo "2️⃣ RabbitMQ Queue Status:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Check if RabbitMQ is accessible
RABBITMQ_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -u guest:guest http://localhost:15672/api/overview)
if [ "$RABBITMQ_STATUS" != "200" ]; then
    echo "❌ RabbitMQ Management API not accessible"
    echo "   Try: docker ps | grep rabbitmq"
    exit 1
fi

echo "✅ RabbitMQ is running"
echo ""

# Check queues using rabbitmqadmin or direct API
echo "Checking Garmin queues..."

# Try to get queue info
API_QUEUE=$(docker exec rabbitmq rabbitmqctl list_queues name messages consumers 2>/dev/null | grep "q.sathishprojects.garmin.api.events" || echo "")
OPS_QUEUE=$(docker exec rabbitmq rabbitmqctl list_queues name messages consumers 2>/dev/null | grep "q.sathishprojects.garmin.ops.events" || echo "")

if [ -z "$API_QUEUE" ]; then
    echo "❌ q.sathishprojects.garmin.api.events: DOES NOT EXIST"
    echo "   → eventstracker must be started to provision this queue"
else
    echo "✅ q.sathishprojects.garmin.api.events: EXISTS"
    echo "   $API_QUEUE"
fi

if [ -z "$OPS_QUEUE" ]; then
    echo "❌ q.sathishprojects.garmin.ops.events: DOES NOT EXIST"
    echo "   → eventstracker must be started to provision this queue"
else
    echo "✅ q.sathishprojects.garmin.ops.events: EXISTS"
    echo "   $OPS_QUEUE"
fi

echo ""
echo "3️⃣ Database Checks:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Check runs-app database
RUNS_APP_COUNT=$(psql -h localhost -p 5443 -U postgres -d runsapp_db -t -c "SELECT COUNT(*) FROM garmin_run;" 2>/dev/null | xargs)
if [ $? -eq 0 ]; then
    echo "✅ runs-app database: $RUNS_APP_COUNT garmin runs"
    
    # Get latest run
    LATEST_RUN=$(psql -h localhost -p 5443 -U postgres -d runsapp_db -t -c "SELECT activity_id, activity_name, created_at FROM garmin_run ORDER BY created_at DESC LIMIT 1;" 2>/dev/null)
    if [ ! -z "$LATEST_RUN" ]; then
        echo "   Latest: $LATEST_RUN"
    fi
else
    echo "❌ runs-app database: Cannot connect"
fi

# Check eventstracker database
EVENTSTRACKER_COUNT=$(psql -h localhost -p 5442 -U postgres -d eventstracker -t -c "SELECT COUNT(*) FROM domain_event WHERE event_type='GARMIN';" 2>/dev/null | xargs)
if [ $? -eq 0 ]; then
    echo "✅ eventstracker database: $EVENTSTRACKER_COUNT Garmin events"
else
    echo "❌ eventstracker database: Cannot connect"
fi

# Check runs-ai-analyzer database
ANALYZER_COUNT=$(psql -h localhost -p 5444 -U postgres -d runs-ai-analyzer -t -c "SELECT COUNT(*) FROM analysis_processing_log;" 2>/dev/null | xargs)
if [ $? -eq 0 ]; then
    echo "✅ runs-ai-analyzer database: $ANALYZER_COUNT processing logs"
    
    # Check status breakdown
    echo ""
    echo "   Processing Status Breakdown:"
    psql -h localhost -p 5444 -U postgres -d runs-ai-analyzer -c "SELECT processing_status, COUNT(*) FROM analysis_processing_log GROUP BY processing_status;" 2>/dev/null | grep -v "^$" | tail -n +3 | head -n -2
else
    echo "❌ runs-ai-analyzer database: Cannot connect"
fi

echo ""
echo "4️⃣ Diagnosis:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ -z "$OPS_QUEUE" ]; then
    echo "🚨 PROBLEM: RabbitMQ queues not provisioned!"
    echo ""
    echo "📋 SOLUTION:"
    echo "   1. Stop all services (Ctrl+C)"
    echo "   2. Start eventstracker FIRST:"
    echo "      cd /Users/skminfotech/IdeaProjects/eventstracker"
    echo "      ./mvnw spring-boot:run"
    echo "   3. Wait for: 'Declared queue: q.sathishprojects.garmin.ops.events'"
    echo "   4. Then start runs-app"
    echo "   5. Then start runs-ai-analyzer"
elif [ "$ANALYZER_COUNT" = "0" ]; then
    echo "⚠️  Queues exist but no processing logs in runs-ai-analyzer"
    echo ""
    echo "📋 Check:"
    echo "   1. runs-ai-analyzer logs for 'Received Garmin event message'"
    echo "   2. runs-app logs for 'Published SUCCESS event to OPS queue'"
    echo "   3. Import a new CSV file to trigger event flow"
else
    echo "✅ Everything looks good!"
    echo ""
    echo "📊 Data Flow Summary:"
    echo "   runs-app: $RUNS_APP_COUNT runs"
    echo "   eventstracker: $EVENTSTRACKER_COUNT events"
    echo "   runs-ai-analyzer: $ANALYZER_COUNT processing logs"
fi

echo ""
