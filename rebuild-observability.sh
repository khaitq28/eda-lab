#!/bin/bash

set -e

echo "========================================"
echo "Rebuilding with Observability"
echo "========================================"
echo ""

# Step 1: Clean and build all services


echo "✅ Build complete"
echo ""

# Step 2: Stop existing containers
echo "Step 2: Stopping existing containers..."
docker compose down
echo "✅ Containers stopped"
echo ""

# Step 3: Remove old images to force rebuild
echo "Step 3: Removing old images..."
docker rmi eda-lab-ingestion-service eda-lab-validation-service eda-lab-enrichment-service eda-lab-audit-service eda-lab-notification-service 2>/dev/null || true
echo "✅ Old images removed (if they existed)"
echo ""

# Step 4: Rebuild and start with fresh images
echo "Step 4: Starting services with fresh images (this may take 3-5 minutes)..."
docker compose up --build -d
echo "✅ Services started"
echo ""

# Step 5: Wait for services to be ready
echo "Step 5: Waiting for services to initialize (60 seconds)..."
sleep 60
echo ""

# Step 6: Check service health
echo "Step 6: Checking service health..."
for service in ingestion validation enrichment audit notification; do
    echo -n "  ${service}-service: "
    if docker ps | grep -q "eda-${service}-service"; then
        echo "✅ Running"
    else
        echo "❌ Not running"
    fi
done
echo ""

# Step 7: Test JSON logging
echo "Step 7: Testing JSON logging..."
echo "Sample log from ingestion-service:"
SAMPLE_LOG=$(docker logs eda-ingestion-service 2>&1 | grep -E '^\{' | tail -1)
if [ -n "$SAMPLE_LOG" ]; then
    echo "$SAMPLE_LOG" | jq '.' 2>/dev/null
    echo "✅ JSON logging is working!"
else
    echo "⚠️  No JSON logs yet. Services may still be starting..."
    echo "Wait 30 more seconds and check manually:"
    echo "  docker logs eda-ingestion-service 2>&1 | tail -20"
fi
echo ""

# Step 8: Test API endpoint
echo "Step 8: Testing API endpoint..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/actuator/health)
if [ "$HTTP_CODE" = "200" ]; then
    echo "✅ Ingestion service is responding (HTTP $HTTP_CODE)"
else
    echo "⚠️  Ingestion service returned HTTP $HTTP_CODE"
fi
echo ""

echo "========================================"
echo "Rebuild Complete!"
echo "========================================"
echo ""
echo "Next steps:"
echo "1. Wait another 30 seconds for services to fully initialize"
echo "2. Run: ./test-observability.sh"
echo ""
echo "If test still fails, check logs:"
echo "  docker logs eda-lab-ingestion-service-1"
echo "  docker logs eda-lab-validation-service-1"
echo ""
