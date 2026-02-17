#!/bin/bash

echo "=========================================="
echo "Clean All Databases - EDA Lab"
echo "=========================================="
echo ""
echo "This script will DELETE all data from all service databases."
echo "Tables and schemas will remain intact."

echo ""
echo "Starting database cleanup..."
echo ""

# Function to clean a database
clean_database() {
    local container=$1
    local db_name=$2
    local service_name=$3
    
    echo "=========================================="
    echo "Cleaning: $service_name ($db_name)"
    echo "=========================================="
    
    # Get all tables in the database (excluding flyway schema history)
    tables=$(docker exec -it $container psql -U postgres -d $db_name -t -c "
        SELECT string_agg(tablename, ',') 
        FROM pg_tables 
        WHERE schemaname='public' 
        AND tablename != 'flyway_schema_history';
    " | tr -d '[:space:]')
    
    if [ -z "$tables" ]; then
        echo "  ℹ️  No tables to clean in $db_name"
    else
        echo "  📋 Tables found: $tables"
        
        # Truncate all tables (CASCADE to handle foreign keys)
        IFS=',' read -ra TABLE_ARRAY <<< "$tables"
        for table in "${TABLE_ARRAY[@]}"; do
            if [ ! -z "$table" ]; then
                echo "  🗑️  Truncating table: $table"
                docker exec -it $container psql -U postgres -d $db_name -c "TRUNCATE TABLE $table CASCADE;" > /dev/null 2>&1
            fi
        done
        
        echo "  ✅ $service_name database cleaned"
    fi
    echo ""
}

# Clean all databases
clean_database "eda-postgres-ingestion" "ingestion_db" "ingestion-service"
clean_database "eda-postgres-validation" "validation_db" "validation-service"
clean_database "eda-postgres-enrichment" "enrichment_db" "enrichment-service"
clean_database "eda-postgres-audit" "audit_db" "audit-service"
clean_database "eda-postgres-notification" "notification_db" "notification-service"

echo "=========================================="
echo "Cleanup Complete!"
echo "=========================================="
echo ""
echo "Summary:"
echo "  ✅ ingestion-service:    All data deleted (documents, outbox_events)"
echo "  ✅ validation-service:   All data deleted (processed_events, outbox_events)"
echo "  ✅ enrichment-service:   All data deleted (processed_events, outbox_events)"
echo "  ✅ audit-service:        All data deleted (audit_log)"
echo "  ✅ notification-service: All data deleted (notification_history)"
echo ""
echo "Tables and schemas remain intact."
echo "Flyway migration history preserved."
echo ""
echo "You can now run fresh tests!"
echo ""
