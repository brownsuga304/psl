#!/bin/bash
#
# Test Google Managed Kafka publisher + subscriber
#
# Usage: ./test-managed-kafka-subscriber.sh BOOTSTRAP_SERVER TOPIC_NAME [NUM_MESSAGES]
#
# Example:
#   ./test-managed-kafka-subscriber.sh bootstrap.test-kafka.us-central1.managedkafka.franchise-5bd11.cloud.goog:9092 test-topic 5
#
# Prerequisites:
#   1. gcloud auth application-default login
#   2. Create a topic in your Managed Kafka cluster
#

set -e

if [ $# -lt 2 ]; then
    echo "Usage: ./test-managed-kafka-subscriber.sh BOOTSTRAP_SERVER TOPIC_NAME [NUM_MESSAGES]"
    echo ""
    echo "Example:"
    echo "  ./test-managed-kafka-subscriber.sh bootstrap.test-kafka.us-central1.managedkafka.franchise-5bd11.cloud.goog:9092 test-topic 5"
    echo ""
    echo "Prerequisites:"
    echo "  1. Run: gcloud auth application-default login"
    echo "  2. Create a topic in your Managed Kafka cluster"
    exit 1
fi

BOOTSTRAP=$1
TOPIC=$2
NUM_MESSAGES=${3:-5}

echo "Building and running subscriber test..."
echo ""

mvn -q compile test-compile -pl google-cloud-pubsublite -am -DskipTests

mvn -q exec:java -pl google-cloud-pubsublite \
    -Dexec.mainClass="com.google.cloud.pubsublite.cloudpubsub.ManagedKafkaSubscriberTest" \
    -Dexec.classpathScope=test \
    -Dexec.args="$BOOTSTRAP $TOPIC $NUM_MESSAGES"
