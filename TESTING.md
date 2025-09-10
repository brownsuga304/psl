# Testing Kafka Publishing Support

This document describes how to test the new Kafka publishing functionality in the Java Pub/Sub Lite client.

## Quick Start

The Kafka publishing feature allows you to use the same Pub/Sub Lite client API to publish messages to either:
- **Pub/Sub Lite** (existing functionality, default)
- **Apache Kafka** (new functionality)
- **Google Managed Service for Apache Kafka** (new functionality)

## Testing Options

### 1. 🧪 Unit Tests (Fastest)

Run the basic unit tests to verify the functionality:

```bash
cd google-cloud-pubsublite
mvn test -Dtest=KafkaBackendTest
```

**What it tests:**
- Backend selection logic
- Settings configuration
- Factory creation
- Backward compatibility

### 2. 🐳 Docker Compose (Recommended for Local Development)

Test against a real Kafka cluster using Docker:

```bash
# Start Kafka environment
./test-kafka.sh

# In another terminal, run your test code
cd examples
javac -cp "../google-cloud-pubsublite/target/classes:$HOME/.m2/repository/com/google/cloud/google-cloud-pubsublite/1.15.15-SNAPSHOT/google-cloud-pubsublite-1.15.15-SNAPSHOT.jar" KafkaPublisherExample.java
java -cp "../google-cloud-pubsublite/target/classes:$HOME/.m2/repository/com/google/cloud/google-cloud-pubsublite/1.15.15-SNAPSHOT/google-cloud-pubsublite-1.15.15-SNAPSHOT.jar" KafkaPublisherExample

# View messages in Kafka UI: http://localhost:8080
# Or use console consumer:
docker-compose --profile consumer up kafka-consumer

# Cleanup
docker-compose down
```

**What it tests:**
- Full end-to-end publishing
- Message format conversion
- Kafka integration
- Error handling

### 3. 🏗️ Integration Tests (Most Comprehensive)

Run integration tests with embedded Kafka (requires Docker):

```bash
cd google-cloud-pubsublite
mvn test -Dtest=KafkaIntegrationTest
```

**What it tests:**
- Message publishing and verification
- Attribute mapping to headers
- Ordering key handling
- Multiple message scenarios
- Error conditions

### 4. ☁️ Google Managed Kafka (Production-like)

Test against actual Google Managed Kafka:

#### Prerequisites:
1. Create a Managed Kafka cluster in Google Cloud Console
2. Create a topic in the cluster  
3. Set up authentication (`gcloud auth application-default login`)

#### Run the example:
```bash
# Update configuration in GoogleManagedKafkaExample.java
cd examples
javac GoogleManagedKafkaExample.java
java GoogleManagedKafkaExample
```

## Configuration Examples

### Basic Kafka (localhost)
```java
Map<String, Object> kafkaProps = new HashMap<>();
kafkaProps.put("bootstrap.servers", "localhost:9092");

PublisherSettings settings = PublisherSettings.newBuilder()
    .setTopicPath(topicPath)
    .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
    .setKafkaProperties(kafkaProps)
    .build();
```

### Google Managed Kafka
```java
Map<String, Object> kafkaProps = new HashMap<>();
kafkaProps.put("bootstrap.servers", "cluster.region.managedkafka.gcp.cloud:9092");
kafkaProps.put("security.protocol", "SASL_SSL");
kafkaProps.put("sasl.mechanism", "OAUTHBEARER");

PublisherSettings settings = PublisherSettings.newBuilder()
    .setTopicPath(topicPath)
    .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
    .setKafkaProperties(kafkaProps)
    .build();
```

### Production Kafka with Advanced Settings
```java
Map<String, Object> kafkaProps = new HashMap<>();
kafkaProps.put("bootstrap.servers", "kafka1:9092,kafka2:9092,kafka3:9092");
kafkaProps.put("acks", "all");
kafkaProps.put("retries", Integer.MAX_VALUE);
kafkaProps.put("enable.idempotence", true);
kafkaProps.put("compression.type", "snappy");
kafkaProps.put("batch.size", 65536);
kafkaProps.put("linger.ms", 50);

PublisherSettings settings = PublisherSettings.newBuilder()
    .setTopicPath(topicPath)
    .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
    .setKafkaProperties(kafkaProps)
    .build();
```

## Message Format Mapping

When you publish a `PubsubMessage`, it gets converted to a Kafka `ProducerRecord`:

| Pub/Sub Field | Kafka Field | Notes |
|---------------|-------------|-------|
| `data` | `value` | Message payload |
| `orderingKey` | `key` | Used for partition routing |
| `attributes` | `headers` | Key-value metadata |
| `eventTime` | Header: `pubsublite.event_time` | Special header |

## Verification

### Check Messages in Kafka

```bash
# Using Kafka console consumer
docker-compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic my-kafka-topic \
  --from-beginning \
  --property print.headers=true \
  --property print.timestamp=true

# Using Kafka UI
# Navigate to http://localhost:8080 in your browser
```

### Check Message IDs

The returned message ID encodes Kafka metadata:
```
Format: "partition:offset"
Example: "0:42" means partition 0, offset 42
```

## Troubleshooting

### Common Issues:

1. **Connection refused**
   - Ensure Kafka is running: `docker-compose ps`
   - Check port accessibility: `telnet localhost 9092`

2. **Authentication errors**
   - For Google Managed Kafka: `gcloud auth application-default login`
   - Check IAM permissions for the service account

3. **Topic doesn't exist**
   - Create topic: `docker-compose exec kafka kafka-topics --bootstrap-server localhost:9092 --create --topic my-topic --partitions 3 --replication-factor 1`

4. **Serialization errors**
   - Ensure you're using `ByteArraySerializer` for both key and value

### Debug Logging

Enable debug logging:
```java
System.setProperty("org.apache.kafka.clients.producer.loglevel", "DEBUG");
```

## Performance Testing

For performance testing, consider:

```java
// High-throughput settings
Map<String, Object> kafkaProps = new HashMap<>();
kafkaProps.put("bootstrap.servers", "localhost:9092");
kafkaProps.put("batch.size", 65536);           // 64KB batches
kafkaProps.put("linger.ms", 10);               // 10ms batching
kafkaProps.put("compression.type", "snappy");   // Fast compression
kafkaProps.put("buffer.memory", 67108864);     // 64MB buffer
```

## Next Steps

- Run the tests to verify your implementation works
- Try publishing to both Pub/Sub Lite and Kafka with the same code
- Implement the subscriber functionality using similar patterns
- Consider adding metrics and monitoring for production use