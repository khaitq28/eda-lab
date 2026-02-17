#!/bin/bash

BASE_URL="http://localhost:8081"

echo "=== Test 1: Upload document ==="
RESPONSE=$(curl -s -X POST $BASE_URL/api/documents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "failed.pdf",
    "contentType": "application/pdf",
    "fileSize": 1024,
    "metadata": {
      "department": "engineering",
      "project": "eda-lab"
    },
    "uploadedBy": "test@example.com"
  }')

echo $RESPONSE | jq '.'

# Extract document ID
DOC_ID=$(echo $RESPONSE | jq -r '.data.id')
echo ""
echo "Created document ID: $DOC_ID"

echo ""
