#!/bin/bash

# Test script for VALID document (should be VALIDATED)
# - Random document name (< 30 chars)
# - Random file size
# - Content type: application/pdf

BASE_URL="http://localhost:8081/api"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo "============================================"
echo "Test: Valid PDF Document (Expected: VALIDATED)"
echo "============================================"
echo ""

# Generate random values
RANDOM_NUM=$(date +%s%N | cut -b10-16)
DOC_NAME="doc-${RANDOM_NUM}.pdf"  # e.g., "doc-1234567.pdf" (16 chars < 30)
FILE_SIZE=$((1024 + RANDOM % 1000000))  # Random size between 1KB and ~1MB

echo -e "${BLUE}Test Parameters:${NC}"
echo "  Document Name: $DOC_NAME (length: ${#DOC_NAME})"
echo "  Content Type: application/pdf"
echo "  File Size: $FILE_SIZE bytes"
echo "  Expected Result: ✅ VALIDATED"
echo ""

# Upload document
echo -e "${BLUE}Uploading document...${NC}"
RESPONSE=$(curl -s -X POST $BASE_URL/documents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "'$DOC_NAME'",
    "contentType": "application/pdf",
    "fileSize": '$FILE_SIZE',
    "metadata": {
      "test": "valid-pdf",
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
echo -e "${GREEN}✅ Document uploaded successfully${NC}"
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
echo "   Expected: 'Document VALIDATED: documentId=$DOC_ID'"
echo ""

echo "2️⃣  Check processed_events table:"
echo "   docker exec -it validation-db psql -U postgres -d validation_db -c \\"
echo "   \"SELECT event_id, event_type, aggregate_id, processed_at FROM processed_events WHERE aggregate_id = '$DOC_ID';\""
echo ""
echo "   Expected: 1 row with aggregate_id = $DOC_ID"
echo ""

echo "3️⃣  Check RabbitMQ queues (http://localhost:15672):"
echo "   - document.uploaded.q: Should be empty (message consumed)"
echo "   - document.uploaded.dlq: Should be empty (no failures)"
echo ""

echo "============================================"
echo "Test Complete!"
echo "============================================"
