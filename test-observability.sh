#!/bin/bash

# Test script to verify JSON logging and correlation ID tracing

# Don't exit on error - we want to show all results
set +e

echo "========================================"
echo "Testing Observability Implementation"
echo "========================================"
echo ""

# Test 1: Upload document with correlation ID
echo "Test 1: Uploading document with correlation ID..."
CORRELATION_ID="test-correlation-$(date +%s)"

RESPONSE=$(curl -s -X POST http://localhost:8081/api/v1/documents \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: $CORRELATION_ID" \
  -d '{
    "name": "observability-test.pdf",
    "contentType": "application/pdf",
    "fileSize": 2048,
    "uploadedBy": "test@example.com"
  }')

echo "Response: $RESPONSE"

# Check if response is valid JSON
if echo "$RESPONSE" | jq -e . >/dev/null 2>&1; then
    DOCUMENT_ID=$(echo "$RESPONSE" | jq -r '.data.id')
    echo "✅ Document ID: $DOCUMENT_ID"
else
    echo "❌ Invalid JSON response - service may not be running or needs rebuild"
    echo "Please run: ./rebuild-and-test.sh"
    exit 1
fi

echo "Correlation ID: $CORRELATION_ID"
echo ""

# Wait for event propagation
echo "Waiting 4 seconds for event propagation..."
sleep 4
echo ""

# Test 2: Check JSON logs in ingestion-service
echo "Test 2: Checking JSON logs in ingestion-service..."
echo "Looking for correlation ID: $CORRELATION_ID"
docker logs eda-ingestion-service 2>&1 | grep "$CORRELATION_ID" | tail -5 | while read line; do
  echo "$line" | jq '.' 2>/dev/null || echo "$line"
done
echo ""

# Test 3: Check JSON logs in validation-service
echo "Test 3: Checking JSON logs in validation-service..."
docker logs eda-validation-service 2>&1 | grep "$CORRELATION_ID" | tail -5 | while read line; do
  echo "$line" | jq '.' 2>/dev/null || echo "$line"
done
echo ""

# Test 4: Check JSON logs in enrichment-service
echo "Test 4: Checking JSON logs in enrichment-service..."
docker logs eda-enrichment-service 2>&1 | grep "$CORRELATION_ID" | tail -5 | while read line; do
  echo "$line" | jq '.' 2>/dev/null || echo "$line"
done
echo ""

# Test 5: Check JSON logs in audit-service
echo "Test 5: Checking JSON logs in audit-service..."
docker logs eda-audit-service 2>&1 | grep "$CORRELATION_ID" | tail -5 | while read line; do
  echo "$line" | jq '.' 2>/dev/null || echo "$line"
done
echo ""

# Test 6: Check JSON logs in notification-service
echo "Test 6: Checking JSON logs in notification-service..."
docker logs eda-notification-service 2>&1 | grep "$CORRELATION_ID" | tail -5 | while read line; do
  echo "$line" | jq '.' 2>/dev/null || echo "$line"
done
echo ""

# Test 7: Verify JSON format
echo "Test 7: Verifying JSON format..."
echo "Sample log from ingestion-service:"
docker logs eda-ingestion-service 2>&1 | tail -20 | grep -E '^\{' | head -1 | jq '.'
echo ""

# Test 8: Search by document ID
echo "Test 8: Searching logs by document ID: $DOCUMENT_ID..."
echo "Ingestion service:"
docker logs eda-ingestion-service 2>&1 | grep "$DOCUMENT_ID" | wc -l
echo "Validation service:"
docker logs eda-validation-service 2>&1 | grep "$DOCUMENT_ID" | wc -l
echo "Enrichment service:"
docker logs eda-enrichment-service 2>&1 | grep "$DOCUMENT_ID" | wc -l
echo ""

echo "========================================"
echo "Observability Test Complete"
echo "========================================"
echo ""
echo "Summary:"
echo "- Correlation ID: $CORRELATION_ID"
echo "- Document ID: $DOCUMENT_ID"
echo ""
echo "To trace the full flow, run:"
echo "  docker logs eda-ingestion-service 2>&1 | grep '$CORRELATION_ID'"
echo "  docker logs eda-validation-service 2>&1 | grep '$CORRELATION_ID'"
echo "  docker logs eda-enrichment-service 2>&1 | grep '$CORRELATION_ID'"
echo "  docker logs eda-audit-service 2>&1 | grep '$CORRELATION_ID'"
echo "  docker logs eda-notification-service 2>&1 | grep '$CORRELATION_ID'"
echo ""
