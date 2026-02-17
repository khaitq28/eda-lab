#!/bin/bash

echo "=========================================="
echo "Full EDA Pipeline E2E Test"
echo "=========================================="
echo ""
echo "This test validates the complete event flow:"
echo "  1. ingestion-service: Upload document → Outbox → RabbitMQ"
echo "  2. validation-service: Consume → Validate → Outbox → RabbitMQ"
echo "  3. enrichment-service: Consume → Enrich → Outbox → RabbitMQ"
echo "  4. audit-service: Consume ALL events → Store audit log"
echo ""

BASE_URL="http://localhost:8081"
RANDOM_NUM=$(date +%s%N | cut -b10-16)
DOC_NAME="e2e-pipeline-${RANDOM_NUM}.pdf"
FILE_SIZE=$((10240 + RANDOM % 100000))

echo "=========================================="
echo "Step 1: Upload Document"
echo "=========================================="
echo "Document: ${DOC_NAME}"
echo "Content-Type: application/pdf"
echo "File Size: ${FILE_SIZE} bytes"
echo ""

RESPONSE=$(curl -s -X POST $BASE_URL/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "'"${DOC_NAME}"'",
    "contentType": "application/pdf",
    "fileSize": '"${FILE_SIZE}"',
    "metadata": {
      "source": "e2e-pipeline-test",
      "testCase": "full-flow"
    },
    "uploadedBy": "e2e-test@example.com"
  }')

echo "$RESPONSE" | jq '.'

DOC_ID=$(echo "$RESPONSE" | jq -r '.data.id')

if [ "$DOC_ID" == "null" ] || [ -z "$DOC_ID" ]; then
  echo ""
  echo "❌ Failed to upload document"
  exit 1
fi

echo ""
echo "✅ Document uploaded successfully"
echo "Document ID: ${DOC_ID}"
echo ""

echo "Waiting for event processing pipeline (40 seconds)..."
echo "  → ingestion-service: Document → Outbox → RabbitMQ (DocumentUploaded)"
echo "  → validation-service: Consume → Validate → Outbox → RabbitMQ (DocumentValidated)"
echo "  → enrichment-service: Consume → Enrich → Outbox → RabbitMQ (DocumentEnriched)"
echo "  → audit-service: Consume ALL 3 events → Store in audit_log"
echo ""

for i in {1..40}; do
  echo -n "."
  sleep 1
done
echo ""
echo ""

echo "=========================================="
echo "Step 2: Verify Ingestion Service"
echo "=========================================="

echo ""
echo "Ingestion Service - Documents Table:"
docker exec -it ingestion-db psql -U postgres -d ingestion_db -c \
  "SELECT id, name, content_type, status, uploaded_at FROM documents WHERE id = '${DOC_ID}';"

echo ""
echo "Ingestion Service - Outbox Events:"
docker exec -it ingestion-db psql -U postgres -d ingestion_db -c \
  "SELECT event_id, event_type, status, created_at, sent_at FROM outbox_events WHERE aggregate_id = '${DOC_ID}' ORDER BY created_at;"

echo ""
echo "=========================================="
echo "Step 3: Verify Validation Service"
echo "=========================================="

echo ""
echo "Validation Service - Processed Events:"
docker exec -it validation-db psql -U postgres -d validation_db -c \
  "SELECT event_id, event_type, processed_at FROM processed_events WHERE aggregate_id = '${DOC_ID}';"

echo ""
echo "Validation Service - Outbox Events:"
docker exec -it validation-db psql -U postgres -d validation_db -c \
  "SELECT event_id, event_type, status, created_at, sent_at FROM outbox_events WHERE aggregate_id = '${DOC_ID}' ORDER BY created_at;"

echo ""
echo "=========================================="
echo "Step 4: Verify Enrichment Service"
echo "=========================================="

echo ""
echo "Enrichment Service - Processed Events:"
docker exec -it enrichment-db psql -U postgres -d enrichment_db -c \
  "SELECT event_id, event_type, processed_at FROM processed_events WHERE aggregate_id = '${DOC_ID}';"

echo ""
echo "Enrichment Service - Outbox Events:"
docker exec -it enrichment-db psql -U postgres -d enrichment_db -c \
  "SELECT event_id, event_type, status, created_at, sent_at FROM outbox_events WHERE aggregate_id = '${DOC_ID}' ORDER BY created_at;"

echo ""
echo "=========================================="
echo "Step 5: Verify Audit Service ⭐"
echo "=========================================="

echo ""
echo "Audit Service - Complete Audit Log for Document ${DOC_ID}:"
docker exec -it audit-db psql -U postgres -d audit_db -c \
  "SELECT event_id, event_type, routing_key, received_at FROM audit_log WHERE aggregate_id = '${DOC_ID}' ORDER BY received_at;"

echo ""
echo "Audit Service - Event Count:"
docker exec -it audit-db psql -U postgres -d audit_db -c \
  "SELECT COUNT(*) as event_count FROM audit_log WHERE aggregate_id = '${DOC_ID}';"

echo ""
echo "=========================================="
echo "Step 6: Query Audit REST API"
echo "=========================================="

echo ""
echo "GET /api/v1/audit?documentId=${DOC_ID}"
echo ""
curl -s "http://localhost:8084/api/v1/audit?documentId=${DOC_ID}" | jq '.'

echo ""
echo "GET /api/v1/audit/timeline/${DOC_ID}"
echo ""
curl -s "http://localhost:8084/api/v1/audit/timeline/${DOC_ID}" | jq '.'

echo ""
echo "GET /api/v1/audit/stats"
echo ""
curl -s "http://localhost:8084/api/v1/audit/stats" | jq '.'

echo ""
echo "=========================================="
echo "Step 7: Check RabbitMQ Queues"
echo "=========================================="
echo ""
echo "RabbitMQ Management UI: http://localhost:15672"
echo "  Username: guest"
echo "  Password: guest"
echo ""
echo "Expected queue states:"
echo "  ✅ document.uploaded.q: 0 messages (consumed by validation-service)"
echo "  ✅ document.validated.q: 0 messages (consumed by enrichment-service)"
echo "  ✅ document.audit.q: 0 messages (consumed by audit-service)"
echo "  ✅ All DLQs: 0 messages (no failures)"
echo ""

echo "=========================================="
echo "Step 8: Check Service Logs"
echo "=========================================="

echo ""
echo "Ingestion Service:"
docker logs ingestion-service 2>&1 | grep "${DOC_ID}" | tail -3

echo ""
echo "Validation Service:"
docker logs validation-service 2>&1 | grep "${DOC_ID}" | tail -3

echo ""
echo "Enrichment Service:"
docker logs enrichment-service 2>&1 | grep "${DOC_ID}" | tail -3

echo ""
echo "Audit Service:"
docker logs audit-service 2>&1 | grep "${DOC_ID}" | tail -5

echo ""
echo "=========================================="
echo "Test Summary"
echo "=========================================="
echo ""
echo "Document ID: ${DOC_ID}"
echo "Document Name: ${DOC_NAME}"
echo ""
echo "Expected Event Flow:"
echo "  1. ✅ DocumentUploaded   → ingestion-service → RabbitMQ"
echo "  2. ✅ DocumentUploaded   → validation-service consumes"
echo "  3. ✅ DocumentValidated  → validation-service → RabbitMQ"
echo "  4. ✅ DocumentValidated  → enrichment-service consumes"
echo "  5. ✅ DocumentEnriched   → enrichment-service → RabbitMQ"
echo "  6. ✅ ALL 3 events       → audit-service consumes & stores"
echo ""
echo "Audit Service Should Have Recorded:"
echo "  - DocumentUploaded (routing key: document.uploaded)"
echo "  - DocumentValidated (routing key: document.validated)"
echo "  - DocumentEnriched (routing key: document.enriched)"
echo ""
echo "If all events appear in audit_log, the EDA pipeline is working! 🎉"
echo ""
echo "=========================================="
echo "Quick Verification Commands"
echo "=========================================="
echo ""
echo "# Check audit timeline via API:"
echo "curl -s \"http://localhost:8084/api/v1/audit/timeline/${DOC_ID}\" | jq '.eventTimeline'"
echo ""
echo "# Check audit log in DB:"
echo "docker exec -it audit-db psql -U postgres -d audit_db -c \"SELECT event_type, routing_key FROM audit_log WHERE aggregate_id = '${DOC_ID}' ORDER BY received_at;\""
echo ""
echo "# Expected output: 3 events (Uploaded, Validated, Enriched)"
echo ""
