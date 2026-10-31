#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=================================================="
echo " Starting QueuePilot PostgreSQL Database..."
echo "=================================================="

# Check if container already exists and clean it up
if sudo docker ps -a --format '{{.Names}}' | grep -q "^queuepilot-postgres$"; then
    echo "Cleaning up existing queuepilot-postgres container..."
    sudo docker rm -f queuepilot-postgres > /dev/null 2>&1 || true
fi

# Run Postgres container with schema mounted
sudo docker run -d \
  --name queuepilot-postgres \
  -e POSTGRES_DB=queuepilot \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=password \
  -p 5432:5432 \
  -v "$DIR/database/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro" \
  postgres:16-alpine

echo "Waiting for database to initialize..."
until sudo docker exec queuepilot-postgres pg_isready -U postgres -d queuepilot > /dev/null 2>&1; do
    sleep 1
done

echo ""
echo " SUCCESS: PostgreSQL is ready on port 5432 with QueuePilot schema!"
echo " Next step: Run './mvnw spring-boot:run' to start the application."
echo "=================================================="
