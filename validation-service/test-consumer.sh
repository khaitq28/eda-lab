#!/bin/bash

# Consumer Testing Script for Validation Service
# Tests idempotency, retry, and DLQ behavior

set -e

INGESTION_URL="http://localhost:8081/api/v1"
VALIDATION_URL="http://localhost:8082"

echo "============================================"
echo "Validation Service Consumer Testing"
echo "============================================"
echo ""

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

# Check if services are running
echo "1. Checking if services are running..."
if curl -s -f "${INGESTION_URL}/health" > /dev/null 2>&1; then
    print_success "Ingestion service is running"
else
    print_error "Ingestion service is NOT running"
    exit 1
fi

if curl -s -f "${VALIDATION_URL}/actuator/health" > /dev/null 2>&1; then
    print_success "Validation service is running"
else
    print_error "Validation service is NOT running"
    exit 1
fi

# Upload a document (will be validated)
echo ""
echo "2. Uploading a document for validation..."
RESPONSE=$(curl -s -X POST "${INGESTION_URL}/documents" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test-consumer-'$(date +%s)'.pdf",
    "contentType": "application/pdf",
    "fileSize": 1024000,
    "metadata": {
      "description": "Test document for consumer validation",
      "category": "testing"
    },
    "uploadedBy": "test-script"
  }')

DOCUMENT_ID=$(echo "$RESPONSE" | grep -o '"id":"[^"]*' | cut -d'"' -f4)

if [ -z "$DOCUMENT_ID" ]; then
    print_error "Failed to upload document"
    echo "Response: $RESPONSE"
    exit 1
fi

print_success "Document uploaded successfully"
print_info "Document ID: $DOCUMENT_ID"

# Extract last character of UUID (for validation prediction)
UUID_NO_DASH=$(echo "$DOCUMENT_ID" | tr -d '-')
LAST_CHAR="${UUID_NO_DASH: -1}"
LAST_DIGIT=$(printf '%d' "'$LAST_CHAR")

if [ $((LAST_DIGIT % 2)) -eq 0 ]; then
    print_info "UUID ends with even digit => Should VALIDATE ✅"
    EXPECTED_RESULT="VALIDATED"
else
    print_info "UUID ends with odd digit => Will trigger RETRY/DLQ ❌"
    EXPECTED_RESULT="RETRY_DLQ"
fi

# Wait for consumer to process
echo ""
echo "3. Waiting for consumer to process event..."
print_info "Consumer should process within 2-3 seconds"
echo -n "   Waiting"
for i in {1..5}; do
    sleep 1
    echo -n "."
done
echo ""
print_success "Wait complete"

# Instructions for verification
echo ""
echo "============================================"
echo "Manual Verification Steps"
echo "============================================"
echo ""

print_info "Check Validation Service Logs"
echo "   docker logs validation-service --tail 50 | grep -A 10 -B 5 '$DOCUMENT_ID'"
echo ""
if [ "$EXPECTED_RESULT" = "VALIDATED" ]; then
    print_success "Expected: 'Document VALIDATED: documentId=$DOCUMENT_ID'"
else
    print_warning "Expected: 'Simulated validation technical failure' + retry attempts"
fi
echo ""

print_info "Check Processed Events Table"
echo "   docker exec -it validation-db psql -U postgres -d validation_db"
echo ""
echo "   SELECT event_id, event_type, aggregate_id, processed_at"
echo "   FROM processed_events"
echo "   WHERE aggregate_id = '$DOCUMENT_ID';"
echo ""
if [ "$EXPECTED_RESULT" = "VALIDATED" ]; then
    print_success "Expected: 1 row with aggregate_id = $DOCUMENT_ID"
else
    print_warning "Expected: May or may not be present (depends on idempotency timing)"
fi
echo ""

print_info "Check RabbitMQ Queues"
echo "   URL: http://localhost:15672"
echo "   Username: guest / Password: guest"
echo ""
echo "   1. Check 'document.uploaded.q' - should be empty (message consumed)"
if [ "$EXPECTED_RESULT" = "VALIDATED" ]; then
    echo "   2. Check 'document.uploaded.dlq' - should be empty (no failures)"
else
    echo "   2. Check 'document.uploaded.dlq' - should have 1 message (after 5 retries)"
fi
echo ""

echo "============================================"
echo "Test Idempotency (Optional)"
echo "============================================"
echo ""
print_warning "To test idempotent behavior:"
echo ""
echo "1. Go to RabbitMQ Management UI: http://localhost:15672"
echo "2. Queues → 'document.uploaded.q' → 'Get messages'"
echo "3. If queue is empty, check 'document.uploaded.dlq'"
echo "4. Copy a message"
echo "5. Exchanges → 'doc.events' → 'Publish message'"
echo "6. Paste the message and set routing key: 'document.uploaded'"
echo "7. Publish"
echo ""
echo "Expected in logs:"
echo "   'Event already processed (idempotent skip): eventId=...'"
echo ""

echo "============================================"
echo "Test Retry/DLQ (Optional)"
echo "============================================"
echo ""
print_warning "To test retry with exponential backoff:"
echo ""
echo "1. Manually publish a message with odd-ending UUID:"
echo ""
echo "   RabbitMQ UI → Exchanges → doc.events → Publish message"
echo ""
echo "   Payload:"
echo '   {'
echo '     "eventId": "00000000-0000-0000-0000-000000000001",'
echo '     "eventType": "DocumentUploaded",'
echo '     "aggregateId": "00000000-0000-0000-0000-000000000001",'
echo '     "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'",'
echo '     "documentName": "test-retry.pdf",'
echo '     "contentType": "application/pdf",'
echo '     "fileSize": 1024'
echo '   }'
echo ""
echo "   Properties:"
echo "   - message_id: 00000000-0000-0000-0000-000000000001"
echo "   - content_type: application/json"
echo "   - headers:"
echo "     - eventType: DocumentUploaded"
echo "     - aggregateId: 00000000-0000-0000-0000-000000000001"
echo ""
echo "   Routing key: document.uploaded"
echo ""
echo "2. Watch logs for retry attempts:"
echo "   docker logs validation-service -f"
echo ""
echo "3. Expected retry schedule:"
echo "   - Attempt 1: Immediate"
echo "   - Attempt 2: +1s delay"
echo "   - Attempt 3: +2s delay"
echo "   - Attempt 4: +4s delay"
echo "   - Attempt 5: +8s delay"
echo "   - After 5 attempts: Message goes to DLQ"
echo ""

echo "============================================"
echo "Test Complete!"
echo "============================================"
