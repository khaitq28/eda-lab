#!/bin/bash

# Master test script - runs all validation tests

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "============================================"
echo "Validation Service - Test Suite"
echo "============================================"
echo ""

# Check if services are running
echo "Checking if services are running..."
if ! curl -s -f http://localhost:8081/actuator/health > /dev/null 2>&1; then
    echo -e "${RED}❌ Ingestion service is NOT running${NC}"
    echo "Start it with: docker compose up"
    exit 1
fi

if ! curl -s -f http://localhost:8082/actuator/health > /dev/null 2>&1; then
    echo -e "${RED}❌ Validation service is NOT running${NC}"
    echo "Start it with: docker compose up"
    exit 1
fi

echo -e "${GREEN}✅ All services are running${NC}"
echo ""

# Test 1: Valid PDF document
echo "============================================"
echo "Test 1: Valid PDF Document"
echo "============================================"
echo ""
bash /Users/quangkhai/Desktop/DATA/WORKSPACE/eda-lab/ingestion-service/test-valid-document.sh

echo ""
echo ""
echo "Press Enter to continue to Test 2..."
read

# Test 2: Invalid document format
echo ""
echo "============================================"
echo "Test 2: Invalid Document Format"
echo "============================================"
echo ""
bash /Users/quangkhai/Desktop/DATA/WORKSPACE/eda-lab/ingestion-service/test-invalid-document.sh

echo ""
echo ""

# Test 3: Document name too long (edge case)
echo "============================================"
echo "Test 3: Document Name Too Long (Edge Case)"
echo "============================================"
echo ""

LONG_NAME="this-is-a-very-long-document-name-that-exceeds-the-limit.pdf"
echo "Document Name: $LONG_NAME (length: ${#LONG_NAME})"
echo "Expected: ❌ REJECTED (name > 30 characters)"
echo ""

RESPONSE=$(curl -s -X POST http://localhost:8081/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "'$LONG_NAME'",
    "contentType": "application/pdf",
    "fileSize": 1024,
    "metadata": {"test": "name-too-long"},
    "uploadedBy": "test-automation"
  }')

echo "$RESPONSE" | jq '.'
DOC_ID=$(echo "$RESPONSE" | jq -r '.id // .data.id // empty')

if [ -n "$DOC_ID" ]; then
    echo ""
    echo "Document ID: $DOC_ID"
    echo "Waiting 3 seconds for processing..."
    sleep 3
    echo ""
    echo "Check logs: docker logs validation-service --tail 10 | grep '$DOC_ID'"
    echo "Expected: 'Document REJECTED: Document name too long'"
fi

echo ""
echo ""

# Summary
echo "============================================"
echo "Test Suite Summary"
echo "============================================"
echo ""
echo "Tests completed:"
echo "  1. ✅ Valid PDF document → Should be VALIDATED"
echo "  2. ❌ Invalid format (non-PDF) → Should be REJECTED"
echo "  3. ❌ Name too long (> 30 chars) → Should be REJECTED"
echo ""
echo "To verify results, check:"
echo "  - Validation service logs: docker logs validation-service -f"
echo "  - RabbitMQ UI: http://localhost:15672 (guest/guest)"
echo "  - Database: docker exec -it validation-db psql -U postgres -d validation_db"
echo ""
echo "============================================"
echo "All Tests Complete!"
echo "============================================"
