# Google Managed Kafka Testing Guide

This guide helps you test the new Kafka consumer/publisher support against a real GMK cluster.

## Prerequisites

1. **Google Cloud CLI**: `gcloud` installed and authenticated
2. **Java 8+**: For building and running the client
3. **Maven**: For building the project
4. **Google Managed Kafka cluster**: See setup instructions below

## Quick Start

```bash
# Set environment variables (optional - script will prompt if not set)
export GMK_PROJECT_ID="your-project-id"
export GMK_REGION="us-central1"
export GMK_CLUSTER_ID="test-cluster"
export GMK_TOPIC="test-topic"

# Run the end-to-end test
./test-gmk-e2e.sh
```

## GMK Cluster Setup

### 1. Create a GMK Cluster

```bash
# Basic cluster creation
gcloud managed-kafka clusters create test-cluster \
    --location=us-central1 \
    --cpu=3 \
    --memory=10GiB \
    --subnets=projects/YOUR_PROJECT/regions/us-central1/subnetworks/default

# For production, specify custom VPC
gcloud managed-kafka clusters create test-cluster \
    --location=us-central1 \
    --cpu=3 \
    --memory=10GiB \
    --subnets=projects/YOUR_PROJECT/regions/us-central1/subnetworks/kafka-subnet
```

### 2. Create a Topic

```bash
gcloud managed-kafka topics create test-topic \
    --cluster=test-cluster \
    --location=us-central1 \
    --partitions=3 \
    --replication-factor=3 \
    --retention-ms=86400000  # 1 day
```

### 3. Verify Setup

```bash
# List clusters
gcloud managed-kafka clusters list --location=us-central1

# List topics
gcloud managed-kafka topics list \
    --cluster=test-cluster \
    --location=us-central1

# Get cluster details
gcloud managed-kafka clusters describe test-cluster \
    --location=us-central1
```

## VPC Access Solutions

GMK clusters are typically deployed in VPCs with restricted access. Choose one:

### Option A: Cloud Shell Tunnel (Recommended)
```bash
# Run in a separate terminal
gcloud cloud-shell ssh -- -L 9092:bootstrap.CLUSTER.REGION.managedkafka.PROJECT.cloud.goog:9092

# Then use localhost:9092 as bootstrap server
```

### Option B: Compute Engine VM Tunnel
```bash
# Create VM in same VPC as GMK cluster
gcloud compute instances create gmk-test-vm \
    --zone=us-central1-a \
    --network=projects/YOUR_PROJECT/global/networks/YOUR_VPC \
    --subnet=projects/YOUR_PROJECT/regions/us-central1/subnetworks/YOUR_SUBNET

# SSH tunnel through VM
gcloud compute ssh gmk-test-vm --zone=us-central1-a -- \
    -L 9092:bootstrap.CLUSTER.REGION.managedkafka.PROJECT.cloud.goog:9092
```

### Option C: VPC Service Controls
If using VPC-SC, add an ingress rule for your development machine's IP.

### Option D: Private Google Access
Enable Private Google Access on your subnet to allow access to *.cloud.goog domains.

## Test Scenarios

### 1. Basic Publisher Test
```bash
# Test only the publisher
./test-gmk-e2e.sh
# Choose option 1 when prompted
```

### 2. Basic Subscriber Test
```bash
# Test only the subscriber (make sure there are existing messages)
./test-gmk-e2e.sh
# Choose option 2 when prompted
```

### 3. End-to-End Test
```bash
# Test publisher then subscriber
./test-gmk-e2e.sh
# Choose option 3 when prompted
```

### 4. Concurrent Test
```bash
# Test publisher and subscriber simultaneously
./test-gmk-e2e.sh
# Choose option 4 when prompted
```

## Manual Testing

### Publisher Example
```java
// Configure for your GMK cluster
String projectId = "your-project";
String region = "us-central1";
String clusterId = "test-cluster";
String topicName = "test-topic";

// Build bootstrap server URL
String bootstrapServers = "bootstrap." + clusterId + "." + region +
    ".managedkafka." + projectId + ".cloud.goog:9092";

// Or use localhost if using tunnel
String bootstrapServers = "localhost:9092";

Map<String, Object> kafkaProps = new HashMap<>();
kafkaProps.put("bootstrap.servers", bootstrapServers);

// Add authentication for direct connection (not needed for localhost tunnel)
kafkaProps.put("security.protocol", "SASL_SSL");
kafkaProps.put("sasl.mechanism", "OAUTHBEARER");
kafkaProps.put("sasl.login.callback.handler.class",
    "com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler");
kafkaProps.put("sasl.jaas.config",
    "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;");

PublisherSettings settings = PublisherSettings.newBuilder()
    .setTopicPath(topicPath)
    .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
    .setKafkaProperties(kafkaProps)
    .build();

Publisher publisher = Publisher.create(settings);
// Use publisher...
```

### Subscriber Example
```java
// Same cluster configuration as publisher

Map<String, Object> kafkaProps = new HashMap<>();
kafkaProps.put("bootstrap.servers", bootstrapServers);
kafkaProps.put("pubsublite.topic.name", topicName);  // Required for subscriber
// Add authentication...

SubscriberSettings settings = SubscriberSettings.newBuilder()
    .setSubscriptionPath(subscriptionPath)
    .setReceiver(receiver)
    .setPerPartitionFlowControlSettings(flowControl)
    .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
    .setKafkaProperties(kafkaProps)
    .build();

Subscriber subscriber = Subscriber.create(settings);
// Use subscriber...
```

## Troubleshooting

### Connection Issues
1. **DNS Resolution**: Verify you can resolve `*.managedkafka.*.cloud.goog`
2. **Network Access**: Check VPC firewall rules
3. **Authentication**: Ensure gcloud is authenticated with correct project

### Common Commands
```bash
# Check cluster status
gcloud managed-kafka clusters describe CLUSTER --location=REGION

# List consumer groups
gcloud managed-kafka consumer-groups list --cluster=CLUSTER --location=REGION

# Reset consumer group (if needed)
gcloud managed-kafka consumer-groups delete GROUP --cluster=CLUSTER --location=REGION

# Monitor cluster metrics in Cloud Monitoring
gcloud monitoring dashboards list --filter="displayName:Kafka"
```

### Performance Tuning

#### Publisher Settings
```java
kafkaProperties.put("batch.size", "16384");         // Batch size in bytes
kafkaProperties.put("linger.ms", "5");              // Batching delay
kafkaProperties.put("compression.type", "snappy");   // Compression
kafkaProperties.put("acks", "all");                 // Durability
```

#### Subscriber Settings
```java
kafkaProperties.put("max.poll.records", "500");     // Messages per poll
kafkaProperties.put("fetch.min.bytes", "1024");     // Min fetch size
kafkaProperties.put("fetch.max.wait.ms", "500");    // Max wait time
```

## Monitoring

### Metrics to Watch
- **Throughput**: Messages/sec published and consumed
- **Latency**: End-to-end message delivery time
- **Consumer Lag**: How far behind consumers are
- **Error Rates**: Failed publishes/consumes

### Cloud Monitoring Queries
```
# Consumer lag
resource.type="kafka_topic"
metric.type="kafka.googleapis.com/consumer/lag"

# Throughput
resource.type="kafka_topic"
metric.type="kafka.googleapis.com/partition/produced_bytes_count"
```

## Production Considerations

1. **Resource Sizing**: Size your GMK cluster based on expected throughput
2. **Partitioning**: Use appropriate partition counts for parallelism
3. **Replication**: Use replication factor 3 for durability
4. **Consumer Groups**: Use different consumer groups for different applications
5. **Error Handling**: Implement proper retry and dead letter queue patterns
6. **Monitoring**: Set up alerts for consumer lag and error rates

## Cost Optimization

- **Cluster Sizing**: Start small and scale based on actual usage
- **Retention**: Set appropriate message retention periods
- **Compression**: Use compression to reduce storage costs
- **Auto-scaling**: Use GMK's auto-scaling features for variable workloads