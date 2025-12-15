#!/bin/bash
#
# Test Google Managed Kafka cursor and topic stats
#
# Usage: ./test-managed-kafka-stats.sh BOOTSTRAP_SERVER TOPIC_NAME [NUM_MESSAGES]
#
# Example:
#   ./test-managed-kafka-stats.sh bootstrap.test-kafka.us-central1.managedkafka.franchise-5bd11.cloud.goog:9092 test-topic 10
#
# Prerequisites:
#   1. gcloud auth application-default login
#   2. Create a topic in your Managed Kafka cluster
#

set -e

if [ $# -lt 2 ]; then
    echo "Usage: ./test-managed-kafka-stats.sh BOOTSTRAP_SERVER TOPIC_NAME [NUM_MESSAGES]"
    echo ""
    echo "Example:"
    echo "  ./test-managed-kafka-stats.sh bootstrap.test-kafka.us-central1.managedkafka.franchise-5bd11.cloud.goog:9092 test-topic 10"
    echo ""
    echo "Prerequisites:"
    echo "  1. Run: gcloud auth application-default login"
    echo "  2. Create a topic in your Managed Kafka cluster"
    exit 1
fi

BOOTSTRAP=$1
TOPIC=$2
NUM_MESSAGES=${3:-10}

echo "Building and running cursor/stats test..."
echo ""

mvn -q compile test-compile -pl google-cloud-pubsublite -am -DskipTests

mvn -q exec:java -pl google-cloud-pubsublite \
    -Dexec.mainClass="com.google.cloud.pubsublite.cloudpubsub.ManagedKafkaStatsTest" \
    -Dexec.classpathScope=test \
    -Dexec.args="$BOOTSTRAP $TOPIC $NUM_MESSAGES"
