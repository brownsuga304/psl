#!/bin/bash

# Script to set up a GMK cluster for testing

set -e

# Default values
PROJECT_ID="${GMK_PROJECT_ID:-$(gcloud config get-value project 2>/dev/null)}"
REGION="${GMK_REGION:-us-central1}"
CLUSTER_ID="${GMK_CLUSTER_ID:-pubsublite-test}"
TOPIC_NAME="${GMK_TOPIC:-test-topic}"
ZONE="${GMK_ZONE:-us-central1-a}"

echo "🚀 GMK Cluster Setup"
echo "==================="
echo "Project:  $PROJECT_ID"
echo "Region:   $REGION"
echo "Cluster:  $CLUSTER_ID"
echo "Topic:    $TOPIC_NAME"
echo ""

if [ -z "$PROJECT_ID" ]; then
    echo "❌ No project ID set. Please run:"
    echo "   gcloud config set project YOUR_PROJECT_ID"
    echo "   or set GMK_PROJECT_ID environment variable"
    exit 1
fi

# Function to check if cluster exists
check_cluster() {
    if gcloud managed-kafka clusters describe $CLUSTER_ID \
        --location=$REGION \
        --project=$PROJECT_ID &>/dev/null; then
        return 0
    else
        return 1
    fi
}

# Function to check if topic exists
check_topic() {
    if gcloud managed-kafka topics describe $TOPIC_NAME \
        --cluster=$CLUSTER_ID \
        --location=$REGION \
        --project=$PROJECT_ID &>/dev/null; then
        return 0
    else
        return 1
    fi
}

echo "🔍 Checking current setup..."

# Check if cluster already exists
if check_cluster; then
    echo "✅ GMK cluster '$CLUSTER_ID' already exists"
    CLUSTER_EXISTS=true
else
    echo "📝 GMK cluster '$CLUSTER_ID' not found - will create"
    CLUSTER_EXISTS=false
fi

# Enable required APIs
echo ""
echo "🔧 Enabling required APIs..."
gcloud services enable managedkafka.googleapis.com --project=$PROJECT_ID
gcloud services enable compute.googleapis.com --project=$PROJECT_ID

# Get default network
echo ""
echo "🌐 Getting network information..."
DEFAULT_NETWORK=$(gcloud compute networks list --filter="name:default" --format="value(selfLink)" --project=$PROJECT_ID | head -1)

if [ -z "$DEFAULT_NETWORK" ]; then
    echo "❌ Default VPC network not found. Please ensure you have a VPC network."
    exit 1
fi

SUBNET=$(gcloud compute networks subnets list \
    --filter="name:default AND region:$REGION" \
    --format="value(selfLink)" \
    --project=$PROJECT_ID | head -1)

if [ -z "$SUBNET" ]; then
    echo "❌ Default subnet not found in region $REGION. Please create a subnet."
    exit 1
fi

echo "📡 Using network: $DEFAULT_NETWORK"
echo "📡 Using subnet:  $SUBNET"

# Create cluster if it doesn't exist
if [ "$CLUSTER_EXISTS" = false ]; then
    echo ""
    echo "🏗️  Creating GMK cluster..."
    echo "This may take 10-15 minutes..."

    gcloud managed-kafka clusters create $CLUSTER_ID \
        --location=$REGION \
        --project=$PROJECT_ID \
        --cpu=3 \
        --memory=10GiB \
        --subnets=$SUBNET

    echo "✅ Cluster created successfully!"
else
    echo "⏭️  Skipping cluster creation (already exists)"
fi

# Wait for cluster to be ready
echo ""
echo "⏳ Waiting for cluster to be ready..."
while true; do
    STATUS=$(gcloud managed-kafka clusters describe $CLUSTER_ID \
        --location=$REGION \
        --project=$PROJECT_ID \
        --format="value(state)" 2>/dev/null || echo "UNKNOWN")

    if [ "$STATUS" = "ACTIVE" ]; then
        echo "✅ Cluster is active and ready"
        break
    elif [ "$STATUS" = "CREATING" ]; then
        echo "   Cluster status: $STATUS (waiting...)"
        sleep 30
    else
        echo "   Cluster status: $STATUS"
        sleep 10
    fi
done

# Create topic if it doesn't exist
echo ""
if check_topic; then
    echo "✅ Topic '$TOPIC_NAME' already exists"
else
    echo "📝 Creating topic '$TOPIC_NAME'..."
    gcloud managed-kafka topics create $TOPIC_NAME \
        --cluster=$CLUSTER_ID \
        --location=$REGION \
        --project=$PROJECT_ID \
        --partitions=3 \
        --replication-factor=3 \
        --retention-ms=86400000

    echo "✅ Topic created successfully!"
fi

# Show cluster information
echo ""
echo "📊 Cluster Information:"
gcloud managed-kafka clusters describe $CLUSTER_ID \
    --location=$REGION \
    --project=$PROJECT_ID \
    --format="table(
        name:label=NAME,
        state:label=STATE,
        createTime:label=CREATED,
        gceClusterConfig.gceClusters[0].network:label=NETWORK
    )"

echo ""
echo "📊 Topic Information:"
gcloud managed-kafka topics list \
    --cluster=$CLUSTER_ID \
    --location=$REGION \
    --project=$PROJECT_ID \
    --format="table(
        name:label=TOPIC,
        partitionCount:label=PARTITIONS,
        replicationFactor:label=REPLICATION
    )"

# Get bootstrap server
BOOTSTRAP_SERVER="bootstrap.$CLUSTER_ID.$REGION.managedkafka.$PROJECT_ID.cloud.goog:9092"

echo ""
echo "🎉 Setup Complete!"
echo "=================="
echo ""
echo "📋 Connection Details:"
echo "   Bootstrap Server: $BOOTSTRAP_SERVER"
echo "   Project ID:       $PROJECT_ID"
echo "   Region:           $REGION"
echo "   Cluster ID:       $CLUSTER_ID"
echo "   Topic:            $TOPIC_NAME"
echo ""
echo "🚀 Ready to test!"
echo "   ./test-gmk-e2e.sh"
echo ""
echo "🔒 For VPC access, you may need to set up a tunnel:"
echo "   gcloud cloud-shell ssh -- -L 9092:$BOOTSTRAP_SERVER"
echo ""
echo "💰 Remember to clean up resources when done:"
echo "   gcloud managed-kafka clusters delete $CLUSTER_ID --location=$REGION"