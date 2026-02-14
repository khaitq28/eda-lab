#!/bin/bash

# Outbox Publisher Testing Script
# This script helps test the complete Outbox Publisher flow

set -e

BASE_URL="http://localhost:8081/api/v1"

echo "============================================"
echo "Outbox Publisher Testing Script"
echo "============================================"
echo ""

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to print colored output
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
if curl -s -f "${BASE_URL}/health" > /dev/null 2>&1; then
    print_success "Ingestion service is running"
else
    print_error "Ingestion service is NOT running"
    echo "   Start it with: docker compose up"
    exit 1
fi

# Upload a document
echo ""
echo "2. Uploading a test document..."
RESPONSE=$(curl -s -X POST "${BASE_URL}/documents" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test-outbox-'$(date +%s)'.pdf",
    "contentType": "application/pdf",
    "fileSize": 1024000,
    "metadata": {
      "description": "Test document for outbox publisher",
      "category": "testing",
      "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'"
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

# Verify document exists
echo ""
echo "3. Verifying document..."
DOC_RESPONSE=$(curl -s "${BASE_URL}/documents/${DOCUMENT_ID}")
DOC_NAME=$(echo "$DOC_RESPONSE" | grep -o '"name":"[^"]*' | cut -d'"' -f4)

if [ -n "$DOC_NAME" ]; then
    print_success "Document verified: $DOC_NAME"
else
    print_error "Failed to verify document"
    exit 1
fi

# Wait for outbox publisher to run (polling interval is 2 seconds)
echo ""
echo "4. Waiting for Outbox Publisher to process event..."
print_info "Polling interval: 2 seconds"
echo -n "   Waiting"
for i in {1..4}; do
    sleep 1
    echo -n "."
done
echo ""
print_success "Wait complete"

# Instructions for manual verification
echo ""
echo "============================================"
echo "Manual Verification Steps"
echo "============================================"
echo ""

print_info "Check Database (Outbox Events)"
echo "   docker exec -it ingestion-db psql -U postgres -d ingestion_db"
echo ""
echo "   SELECT event_id, event_type, aggregate_id, status, retry_count, sent_at"
echo "   FROM outbox_events"
echo "   WHERE aggregate_id = '$DOCUMENT_ID';"
echo ""
print_success "Expected: status = 'SENT', sent_at is populated"
echo ""

print_info "Check RabbitMQ Management UI"
echo "   URL: http://localhost:15672"
echo "   Username: guest"
echo "   Password: guest"
echo ""
echo "   1. Go to 'Exchanges' → 'doc.events'"
echo "   2. Go to 'Queues' → 'document.uploaded.q'"
echo "   3. Click 'Get messages' to see the event"
echo ""
print_success "Expected: 1 message with eventType='DocumentUploaded'"
echo ""

print_info "Check Service Logs"
echo "   docker logs ingestion-service --tail 50 | grep -A 5 -B 5 '$DOCUMENT_ID'"
echo ""
print_success "Expected: 'Successfully published event: eventId=...'"
echo ""

echo "============================================"
echo "Test Retry Logic (Optional)"
echo "============================================"
echo ""
print_warning "To test retry logic with exponential backoff:"
echo ""
echo "1. Stop RabbitMQ:"
echo "   docker compose -f docker-compose.infra.yml stop rabbitmq"
echo ""
echo "2. Upload another document (it will fail to publish)"
echo "   curl -X POST ${BASE_URL}/documents -H 'Content-Type: application/json' \\"
echo "     -d '{\"name\":\"retry-test.pdf\",\"contentType\":\"application/pdf\",\"fileSize\":1024,\"uploadedBy\":\"test\"}'"
echo ""
echo "3. Check logs for retry attempts:"
echo "   docker logs ingestion-service -f"
echo ""
echo "4. Check database for retry_count and next_retry_at:"
echo "   SELECT event_id, retry_count, next_retry_at, last_error FROM outbox_events WHERE status = 'PENDING';"
echo ""
echo "5. Restart RabbitMQ and wait for retry:"
echo "   docker compose -f docker-compose.infra.yml start rabbitmq"
echo ""

echo "============================================"
echo "Test Complete!"
echo "============================================"
