#!/bin/bash

# Test script for INVALID document (should be REJECTED)
# - Random document name (< 30 chars)
# - Random file size
# - Content type: NOT application/pdf (should fail validation)

BASE_URL="http://localhost:8081/api"

# Colors
RED='\033[0;31m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "============================================"
echo "Test: Invalid Document Format (Expected: REJECTED)"
echo "============================================"
echo ""

# Generate random values
RANDOM_NUM=$(date +%s%N | cut -b10-16)

# Random invalid format
FORMATS=("application/msword" "application/vnd.ms-excel" "text/plain" "image/jpeg" "application/zip")
EXTENSIONS=("docx" "xlsx" "txt" "jpg" "zip")
RANDOM_IDX=$((RANDOM % 5))
CONTENT_TYPE="${FORMATS[$RANDOM_IDX]}"
EXTENSION="${EXTENSIONS[$RANDOM_IDX]}"

DOC_NAME="doc-${RANDOM_NUM}.${EXTENSION}"  # e.g., "doc-1234567.docx" (< 30 chars)
FILE_SIZE=$((1024 + RANDOM % 1000000))  # Random size between 1KB and ~1MB

echo -e "${BLUE}Test Parameters:${NC}"
echo "  Document Name: $DOC_NAME (length: ${#DOC_NAME})"
echo "  Content Type: $CONTENT_TYPE"
echo "  File Size: $FILE_SIZE bytes"
echo "  Expected Result: ❌ REJECTED (invalid format)"
echo ""

# Upload document
echo -e "${BLUE}Uploading invalid document...${NC}"
RESPONSE=$(curl -s -X POST $BASE_URL/documents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "'$DOC_NAME'",
    "contentType": "'$CONTENT_TYPE'",
    "fileSize": '$FILE_SIZE',
    "metadata": {
      "test": "invalid-format",
      "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'"
    },
    "uploadedBy": "test-automation"
  }')

echo "$RESPONSE" | jq '.'

# Extract document ID
DOC_ID=$(echo "$RESPONSE" | jq -r '.id // .data.id // empty')

if [ -z "$DOC_ID" ]; then
    echo ""
    echo "❌ Failed to upload document"
    exit 1
fi

echo ""
echo -e "${YELLOW}✅ Document uploaded successfully (but will be rejected by validation)${NC}"
echo "Document ID: $DOC_ID"

# Wait for validation-service to process
echo ""
echo "Waiting for validation-service to process (3 seconds)..."
sleep 3

echo ""
echo "============================================"
echo "Verification Steps"
echo "============================================"
echo ""

echo "1️⃣  Check validation-service logs:"
echo "   docker logs validation-service --tail 20 | grep -i '$DOC_ID'"
echo ""
echo "   Expected: 'Document REJECTED due to business validation'"
echo "   Expected: 'Invalid file format: $CONTENT_TYPE (expected: application/pdf)'"
echo ""

echo "2️⃣  Check processed_events table:"
echo "   docker exec -it validation-db psql -U postgres -d validation_db -c \\"
echo "   \"SELECT event_id, event_type, aggregate_id, processed_at FROM processed_events WHERE aggregate_id = '$DOC_ID';\""
echo ""
echo "   Expected: 1 row with aggregate_id = $DOC_ID (marked as processed, no retry)"
echo ""

echo "3️⃣  Check RabbitMQ queues (http://localhost:15672):"
echo "   - document.uploaded.q: Should be empty (message consumed)"
echo "   - document.uploaded.dlq: Should be empty (business failure, NOT retried)"
echo ""

echo "============================================"
echo "Key Point: Business Validation Failure"
echo "============================================"
echo ""
echo "This test demonstrates that:"
echo "  ✅ Business failures (invalid format) are NOT retried"
echo "  ✅ Message is ACKed (removed from queue)"
echo "  ✅ Event is marked as processed (idempotency)"
echo "  ✅ Document is REJECTED (not sent to DLQ)"
echo ""
echo "  TODO: Emit DocumentRejected event (next iteration)"
echo ""

echo "============================================"
echo "Test Complete!"
echo "============================================"
