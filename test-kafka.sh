#!/bin/bash

# Script to test Kafka publishing functionality

set -e

echo "🚀 Starting Kafka test environment..."

# Clean up any existing containers
echo "🧹 Cleaning up existing containers..."
docker-compose down --remove-orphans

# Start Kafka services
echo "▶️  Starting services..."
docker-compose up -d

echo "⏳ Waiting for services to start..."
sleep 20

# Check if containers are running
echo "🔍 Checking container status..."
docker-compose ps

# Wait for Kafka to be ready
echo "⏳ Waiting for Kafka to be ready..."
for i in {1..30}; do
    if docker-compose exec -T kafka kafka-broker-api-versions --bootstrap-server localhost:9092 > /dev/null 2>&1; then
        echo "✅ Kafka is ready!"
        break
    fi
    echo "   Attempt $i/30 - Kafka not ready yet..."
    sleep 5
done

# Verify Kafka is working
if ! docker-compose exec -T kafka kafka-broker-api-versions --bootstrap-server localhost:9092 > /dev/null 2>&1; then
    echo "❌ Kafka failed to start properly. Checking logs..."
    docker-compose logs kafka
    exit 1
fi

# Create test topic
echo "📝 Creating test topic..."
docker-compose exec -T kafka kafka-topics --bootstrap-server localhost:9092 --create --topic my-kafka-topic --partitions 3 --replication-factor 1 --if-not-exists

# List topics to verify
echo "📋 Available topics:"
docker-compose exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list

echo ""
echo "🎯 Test environment ready! You can now:"
echo "  1. Run your Java publisher code against localhost:9092"
echo "  2. View Kafka UI at http://localhost:8080"
echo "  3. Start a consumer to see messages:"
echo "     docker-compose exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic my-kafka-topic --from-beginning"
echo ""
echo "💡 To run the integration test:"
echo "  mvn test -pl google-cloud-pubsublite -Dtest=KafkaIntegrationTest"
echo ""
echo "🛑 To stop the environment:"
echo "  docker-compose down"