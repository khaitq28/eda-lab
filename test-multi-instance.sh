#!/bin/bash

echo "============================================"
echo "Multi-Instance Load Test"
echo "============================================"

# Scale services
echo "Scaling ingestion-service to 3 instances..."
docker compose up --scale ingestion-service=3 -d

sleep 5

echo "Scaling validation-service to 3 instances..."
docker compose up --scale validation-service=3 -d

sleep 5

# Verify instances
echo ""
echo "Running instances:"
docker compose ps | grep -E "(ingestion|validation)"

# Upload 100 documents
echo ""
echo "Uploading 100 documents..."
for i in {1..100}; do
  RESPONSE=$(curl -s -X POST http://localhost:8081/api/v1/documents \
    -H "Content-Type: application/json" \
    -d '{
      "name": "load-test-'$i'.pdf",
      "contentType": "application/pdf",
      "fileSize": 1024,
      "uploadedBy": "load-test@example.com"
    }')
  
  if [ $((i % 10)) -eq 0 ]; then
    echo "  Uploaded $i documents..."
  fi
done

echo ""
echo "Waiting for processing (30 seconds)..."
sleep 30

# Check results
echo ""
echo "============================================"
echo "Results"
echo "============================================"

echo ""
echo "Ingestion Service Outbox Status:"
docker exec -it ingestion-db psql -U postgres -d ingestion_db -c \
  "SELECT status, COUNT(*) FROM outbox_events GROUP BY status;"

echo ""
echo "Validation Service Processed Events:"
docker exec -it validation-db psql -U postgres -d validation_db -c \
  "SELECT COUNT(DISTINCT event_id) as processed_count FROM processed_events;"

echo ""
echo "Validation Service Outbox Status:"
docker exec -it validation-db psql -U postgres -d validation_db -c \
  "SELECT event_type, status, COUNT(*) FROM outbox_events GROUP BY event_type, status;"

echo ""
echo "Check Duplicate Publishing (should be 0):"
docker exec -it ingestion-db psql -U postgres -d ingestion_db -c \
  "SELECT event_id, COUNT(*) as duplicate_count FROM outbox_events GROUP BY event_id HAVING COUNT(*) > 1;"

echo ""
echo "RabbitMQ Management UI: http://localhost:15672"
echo "  Username: guest"
echo "  Password: guest"
echo "  Check queues: document.uploaded.q, document.validated.q"

echo ""
echo "============================================"
echo "Instance Logs"
echo "============================================"

echo ""
echo "Ingestion Service Instance 1 - Events Published:"
docker logs ingestion-service-1 2>&1 | grep "Successfully published event" | wc -l

echo ""
echo "Ingestion Service Instance 2 - Events Published:"
docker logs ingestion-service-2 2>&1 | grep "Successfully published event" | wc -l

echo ""
echo "Ingestion Service Instance 3 - Events Published:"
docker logs ingestion-service-3 2>&1 | grep "Successfully published event" | wc -l

echo ""
echo "Validation Service Instance 1 - Events Processed:"
docker logs validation-service-1 2>&1 | grep "Document VALIDATED\|Document REJECTED" | wc -l

echo ""
echo "Validation Service Instance 2 - Events Processed:"
docker logs validation-service-2 2>&1 | grep "Document VALIDATED\|Document REJECTED" | wc -l

echo ""
echo "Validation Service Instance 3 - Events Processed:"
docker logs validation-service-3 2>&1 | grep "Document VALIDATED\|Document REJECTED" | wc -l

echo ""
echo "============================================"
echo "Test Complete!"
echo "============================================"
echo ""
echo "Expected Results:"
echo "  - 100 documents uploaded"
echo "  - 100 outbox events with status=SENT in ingestion-db"
echo "  - 100 processed events in validation-db"
echo "  - No duplicate event_id in any table"
echo "  - Events distributed roughly equally across instances"
echo ""
