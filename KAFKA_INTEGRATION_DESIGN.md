# Design Document: Adding Google Managed Kafka Support to Java Pub/Sub Lite Client

## Executive Summary

This document presents a minimally intrusive design for extending the Java Pub/Sub Lite client library to support both Google Cloud Pub/Sub Lite and Google Cloud Managed Service for Apache Kafka. The implementation leverages existing factory patterns in the codebase, requiring modifications to only 2 existing files with approximately 20 lines of changes total, while maintaining 100% backward compatibility.

## Table of Contents
1. [Background and Motivation](#background-and-motivation)
2. [Current Architecture Analysis](#current-architecture-analysis)
3. [Design Principles](#design-principles)
4. [Proposed Solution](#proposed-solution)
5. [Detailed Implementation](#detailed-implementation)
6. [Resource Mapping Strategy](#resource-mapping-strategy)
7. [Authentication and Security](#authentication-and-security)
8. [Testing Strategy](#testing-strategy)
9. [Migration Guide](#migration-guide)
10. [Operational Considerations](#operational-considerations)
11. [Risk Analysis and Mitigation](#risk-analysis-and-mitigation)
12. [Implementation Timeline](#implementation-timeline)

## Background and Motivation

### Current State
The Java Pub/Sub Lite client provides a Cloud Pub/Sub-compatible API for Google Cloud Pub/Sub Lite, a zonal, low-cost messaging service. The client abstracts the underlying gRPC communication and provides familiar Publisher/Subscriber interfaces.

### Business Need
Google Cloud now offers Managed Service for Apache Kafka, reaching GA in November 2024. Users need the flexibility to choose between:
- **Pub/Sub Lite**: For cost-effective, zonal messaging with predefined capacity
- **Managed Kafka**: For applications requiring Kafka-specific features or migrating from existing Kafka deployments

### Goal
Enable users to switch between Pub/Sub Lite and Managed Kafka backends using a simple configuration flag, without changing their application code.

## Current Architecture Analysis

### Key Components

```
┌─────────────────────────────────────────────────────────┐
│                    User Application                      │
├─────────────────────────────────────────────────────────┤
│   Publisher/Subscriber Interfaces (Cloud Pub/Sub API)    │
├─────────────────────────────────────────────────────────┤
│         PublisherSettings / SubscriberSettings           │
├─────────────────────────────────────────────────────────┤
│    PartitionPublisherFactory / PartitionSubscriberFactory│
├─────────────────────────────────────────────────────────┤
│         Internal Publisher/Subscriber Implementations     │
├─────────────────────────────────────────────────────────┤
│                  gRPC Service Clients                    │
└─────────────────────────────────────────────────────────┘
```

### Critical Observations

1. **Factory Pattern Usage**: The codebase uses `PartitionPublisherFactory` and `PartitionSubscriberFactory` as abstraction points for creating partition-specific clients.

2. **Settings-Based Instantiation**: All client creation follows the pattern:
   ```java
   Client.create(Settings) → Settings.instantiate() → Factory → Implementation
   ```

3. **Message Transformation Pipeline**: The `WrappingPublisher` pattern already handles message transformation between API formats.

4. **Partition-Based Architecture**: Both Pub/Sub Lite and Kafka use partitioned topics, simplifying the mapping.

## Design Principles

1. **Minimal Intrusion**: Modify the absolute minimum number of existing files and lines of code
2. **Backward Compatibility**: Ensure 100% compatibility with existing code
3. **Leverage Existing Patterns**: Use factory patterns already present in the codebase
4. **Isolation**: Keep all Kafka-specific code in new, separate files
5. **Opt-in Adoption**: Kafka support must be explicitly enabled by users
6. **Performance Neutrality**: No performance impact on existing Pub/Sub Lite path

## Proposed Solution

### High-Level Approach

The solution injects Kafka support at the existing factory level by:
1. Adding a backend selection enum to settings classes
2. Modifying factory getter methods to return Kafka factories when configured
3. Implementing Kafka factories that adapt Kafka clients to existing interfaces

### Architecture with Kafka Support

```
┌─────────────────────────────────────────────────────────┐
│                    User Application                      │
├─────────────────────────────────────────────────────────┤
│   Publisher/Subscriber Interfaces (Unchanged)            │
├─────────────────────────────────────────────────────────┤
│         PublisherSettings / SubscriberSettings           │
│         [+backend field] [+kafkaProperties field]        │
├─────────────────────────────────────────────────────────┤
│                Factory Selection Point                   │
│    if (kafka) → KafkaFactory else → PubSubLiteFactory   │
├─────────────────┬───────────────────────────────────────┤
│ KafkaPartition   │   ExistingPartition                   │
│ PublisherFactory │   PublisherFactory                    │
├─────────────────┼───────────────────────────────────────┤
│  Kafka Client   │   gRPC Service Clients                │
└─────────────────┴───────────────────────────────────────┘
```

## Detailed Implementation

### 1. Backend Selection Enum (New File)

```java
/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.google.cloud.pubsublite.cloudpubsub;

/**
 * Specifies the messaging backend to use for Publisher and Subscriber clients.
 */
public enum MessagingBackend {
  /**
   * Use Google Cloud Pub/Sub Lite (default).
   * This is the traditional backend with zonal storage and predictable pricing.
   */
  PUBSUB_LITE,
  
  /**
   * Use Google Cloud Managed Service for Apache Kafka.
   * Provides Kafka-compatible API with Google Cloud management.
   */
  MANAGED_KAFKA
}
```

### 2. PublisherSettings Modifications

```java
// File: PublisherSettings.java
// Changes: ADD 2 fields, MODIFY 1 method

@AutoValue
public abstract class PublisherSettings {
  // ============ EXISTING FIELDS (unchanged) ============
  abstract TopicPath topicPath();
  abstract Optional<KeyExtractor> keyExtractor();
  abstract Optional<MessageTransformer<PubsubMessage, Message>> messageTransformer();
  abstract BatchingSettings batchingSettings();
  abstract boolean enableIdempotence();
  abstract boolean enableCompression();
  abstract CredentialsProvider credentialsProvider();
  abstract Framework framework();
  abstract Optional<PublisherServiceClient> serviceClient();
  abstract Optional<AdminClient> adminClient();
  abstract SinglePartitionPublisherBuilder.Builder underlyingBuilder();
  
  // ============ NEW FIELDS (2 additions) ============
  /**
   * The messaging backend to use. Defaults to PUBSUB_LITE for backward compatibility.
   */
  abstract MessagingBackend messagingBackend();
  
  /**
   * Kafka-specific configuration properties. Only used when messagingBackend is MANAGED_KAFKA.
   * Common properties include:
   * - "bootstrap.servers": Kafka broker addresses
   * - "compression.type": Compression algorithm (e.g., "snappy", "gzip")
   * - "max.in.flight.requests.per.connection": Pipelining configuration
   */
  abstract Optional<Map<String, Object>> kafkaProperties();
  
  // ============ MODIFIED BUILDER (add defaults) ============
  public static Builder newBuilder() {
    return new AutoValue_PublisherSettings.Builder()
        .setFramework(Framework.of("CLOUD_PUBSUB_SHIM"))
        .setCredentialsProvider(
            PublisherServiceSettings.defaultCredentialsProviderBuilder().build())
        .setBatchingSettings(DEFAULT_BATCHING_SETTINGS)
        .setEnableIdempotence(true)
        .setEnableCompression(true)
        .setUnderlyingBuilder(SinglePartitionPublisherBuilder.newBuilder())
        .setMessagingBackend(MessagingBackend.PUBSUB_LITE); // NEW: default backend
  }
  
  @AutoValue.Builder
  public abstract static class Builder {
    // Existing builder methods unchanged...
    
    // ============ NEW BUILDER METHODS ============
    /**
     * Sets the messaging backend. Defaults to PUBSUB_LITE.
     */
    public abstract Builder setMessagingBackend(MessagingBackend backend);
    
    /**
     * Sets Kafka-specific properties. Only used when backend is MANAGED_KAFKA.
     */
    public abstract Builder setKafkaProperties(Map<String, Object> properties);
    
    public abstract PublisherSettings build();
  }
  
  // ============ MODIFIED METHOD (3 lines added) ============
  private PartitionPublisherFactory getPartitionPublisherFactory() {
    // NEW: Check backend and return appropriate factory
    if (messagingBackend() == MessagingBackend.MANAGED_KAFKA) {
      return new KafkaPartitionPublisherFactory(this);
    }
    
    // EXISTING CODE (unchanged)
    PublisherServiceClient client = newServiceClient();
    ByteString publisherClientId = UuidBuilder.toByteString(UuidBuilder.generate());
    return new PartitionPublisherFactory() {
      @Override
      public com.google.cloud.pubsublite.internal.Publisher<MessageMetadata> newPublisher(
          Partition partition) throws ApiException {
        // ... existing implementation ...
      }
      
      @Override
      public void close() {
        client.close();
      }
    };
  }
}
```

### 3. KafkaPartitionPublisherFactory Implementation (New File)

```java
/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.google.cloud.pubsublite.cloudpubsub.internal;

import com.google.api.gax.rpc.ApiException;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.pubsublite.MessageMetadata;
import com.google.cloud.pubsublite.Partition;
import com.google.cloud.pubsublite.cloudpubsub.PublisherSettings;
import com.google.cloud.pubsublite.internal.Publisher;
import com.google.cloud.pubsublite.internal.wire.PartitionPublisherFactory;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/**
 * Factory for creating Kafka-based partition publishers.
 * Manages a single KafkaProducer instance shared across all partitions.
 */
class KafkaPartitionPublisherFactory implements PartitionPublisherFactory {
  private final KafkaProducer<byte[], byte[]> kafkaProducer;
  private final PublisherSettings settings;
  private final ConcurrentHashMap<Partition, Publisher<MessageMetadata>> publishers;
  private final String topicName;
  
  KafkaPartitionPublisherFactory(PublisherSettings settings) throws ApiException {
    this.settings = settings;
    this.publishers = new ConcurrentHashMap<>();
    this.topicName = extractKafkaTopicName(settings.topicPath());
    
    Properties props = new Properties();
    
    // Configure Kafka connection
    configureKafkaConnection(props);
    
    // Configure authentication
    configureAuthentication(props);
    
    // Configure producer settings
    configureProducerSettings(props);
    
    // Apply user-provided properties (override defaults)
    if (settings.kafkaProperties().isPresent()) {
      props.putAll(settings.kafkaProperties().get());
    }
    
    try {
      this.kafkaProducer = new KafkaProducer<>(props);
    } catch (Exception e) {
      throw new ApiException(e, null, false);
    }
  }
  
  private void configureKafkaConnection(Properties props) {
    // Extract bootstrap servers from TopicPath or kafka properties
    // TopicPath format for Kafka: projects/{project}/locations/{location}/topics/{topic}
    // We encode Kafka cluster info in location: kafka-{cluster}-{region}
    String location = settings.topicPath().location().toString();
    
    if (location.startsWith("kafka-")) {
      // Extract cluster info from location
      String[] parts = location.substring(6).split("-");
      String cluster = parts[0];
      String region = parts[1];
      String bootstrapServers = String.format(
          "%s.%s.managedkafka.gcp.cloud:9092", cluster, region);
      props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    } else if (settings.kafkaProperties().isPresent() && 
               settings.kafkaProperties().get().containsKey("bootstrap.servers")) {
      // Use explicitly provided bootstrap servers
      props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                settings.kafkaProperties().get().get("bootstrap.servers"));
    } else {
      throw new IllegalArgumentException(
          "Kafka bootstrap servers must be specified either in TopicPath location " +
          "or via kafkaProperties");
    }
  }
  
  private void configureAuthentication(Properties props) {
    // Configure Google Cloud authentication for Managed Kafka
    props.put("security.protocol", "SASL_SSL");
    props.put("sasl.mechanism", "OAUTHBEARER");
    
    try {
      GoogleCredentials credentials = (GoogleCredentials) 
          settings.credentialsProvider().getCredentials();
      
      String saslJaasConfig = String.format(
          "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required " +
          "clientId=\"%s\" " +
          "clientSecret=\"%s\" " +
          "extension_logicalCluster=\"%s\" " +
          "extension_identityPoolId=\"%s\";",
          credentials.getClientId(),
          credentials.getClientSecret(),
          extractClusterName(settings.topicPath()),
          settings.topicPath().project().value()
      );
      
      props.put("sasl.jaas.config", saslJaasConfig);
    } catch (IOException e) {
      throw new RuntimeException("Failed to configure authentication", e);
    }
  }
  
  private void configureProducerSettings(Properties props) {
    // Serialization
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, 
              ByteArraySerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
              ByteArraySerializer.class.getName());
    
    // Performance settings aligned with Pub/Sub Lite defaults
    props.put(ProducerConfig.BATCH_SIZE_CONFIG, 
              settings.batchingSettings().getRequestByteThreshold());
    props.put(ProducerConfig.LINGER_MS_CONFIG,
              settings.batchingSettings().getDelayThresholdDuration().toMillis());
    
    // Compression
    if (settings.enableCompression()) {
      props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip");
    }
    
    // Idempotence
    if (settings.enableIdempotence()) {
      props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
      props.put(ProducerConfig.ACKS_CONFIG, "all");
      props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    }
    
    // Reliability settings
    props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 30000);
    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
  }
  
  @Override
  public Publisher<MessageMetadata> newPublisher(Partition partition) throws ApiException {
    return publishers.computeIfAbsent(partition, p -> 
        new KafkaPartitionPublisher(
            kafkaProducer,
            topicName,
            partition,
            settings.keyExtractor().orElse(KeyExtractor.DEFAULT),
            settings.messageTransformer().orElse(
                MessageTransforms.fromCpsPublishTransformer(KeyExtractor.DEFAULT))
        )
    );
  }
  
  @Override
  public void close() {
    publishers.values().forEach(publisher -> {
      try {
        publisher.stopAsync().awaitTerminated();
      } catch (Exception e) {
        // Log but don't throw
      }
    });
    kafkaProducer.close();
  }
  
  private String extractKafkaTopicName(TopicPath topicPath) {
    // Extract the actual Kafka topic name from TopicPath
    return topicPath.name().value();
  }
  
  private String extractClusterName(TopicPath topicPath) {
    String location = topicPath.location().toString();
    if (location.startsWith("kafka-")) {
      return location.substring(6).split("-")[0];
    }
    return "default-cluster";
  }
}
```

### 4. KafkaPartitionPublisher Implementation (New File)

```java
/*
 * Copyright 2024 Google LLC
 */
package com.google.cloud.pubsublite.cloudpubsub.internal;

import com.google.api.core.ApiFuture;
import com.google.api.core.SettableApiFuture;
import com.google.cloud.pubsublite.Message;
import com.google.cloud.pubsublite.MessageMetadata;
import com.google.cloud.pubsublite.MessageTransformer;
import com.google.cloud.pubsublite.Offset;
import com.google.cloud.pubsublite.Partition;
import com.google.cloud.pubsublite.cloudpubsub.KeyExtractor;
import com.google.cloud.pubsublite.internal.CheckedApiException;
import com.google.cloud.pubsublite.internal.ProxyService;
import com.google.cloud.pubsublite.internal.Publisher;
import com.google.cloud.pubsublite.proto.PubSubMessage;
import com.google.pubsub.v1.PubsubMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;

/**
 * Adapts a Kafka producer to the internal Publisher interface for a specific partition.
 */
class KafkaPartitionPublisher extends ProxyService 
    implements Publisher<MessageMetadata> {
  
  private final KafkaProducer<byte[], byte[]> producer;
  private final String topicName;
  private final Partition partition;
  private final KeyExtractor keyExtractor;
  private final MessageTransformer<PubsubMessage, Message> transformer;
  private final ConcurrentLinkedQueue<SettableApiFuture<MessageMetadata>> pendingFutures;
  
  KafkaPartitionPublisher(
      KafkaProducer<byte[], byte[]> producer,
      String topicName,
      Partition partition,
      KeyExtractor keyExtractor,
      MessageTransformer<PubsubMessage, Message> transformer) {
    this.producer = producer;
    this.topicName = topicName;
    this.partition = partition;
    this.keyExtractor = keyExtractor;
    this.transformer = transformer;
    this.pendingFutures = new ConcurrentLinkedQueue<>();
  }
  
  @Override
  public ApiFuture<MessageMetadata> publish(PubSubMessage message) {
    if (state() == State.FAILED) {
      return ApiFutures.immediateFailedFuture(
          new CheckedApiException("Publisher has failed", Code.FAILED_PRECONDITION).underlying);
    }
    
    try {
      // Convert to Kafka ProducerRecord
      ProducerRecord<byte[], byte[]> record = convertToKafkaRecord(message);
      
      // Create future for response
      SettableApiFuture<MessageMetadata> future = SettableApiFuture.create();
      pendingFutures.add(future);
      
      // Send to Kafka
      producer.send(record, (metadata, exception) -> {
        pendingFutures.remove(future);
        
        if (exception != null) {
          CheckedApiException apiException = new CheckedApiException(exception);
          future.setException(apiException.underlying);
          
          // If this is a permanent error, fail the publisher
          if (isPermanentError(exception)) {
            onPermanentError(apiException);
          }
        } else {
          // Convert Kafka metadata to MessageMetadata
          MessageMetadata messageMetadata = MessageMetadata.of(
              Partition.of(metadata.partition()),
              Offset.of(metadata.offset())
          );
          future.set(messageMetadata);
        }
      });
      
      return future;
      
    } catch (Exception e) {
      CheckedApiException apiException = new CheckedApiException(e);
      onPermanentError(apiException);
      return ApiFutures.immediateFailedFuture(apiException.underlying);
    }
  }
  
  @Override
  public void flush() {
    producer.flush();
  }
  
  @Override
  public void cancelOutstandingPublishes() {
    CheckedApiException exception = new CheckedApiException(
        "Publisher is shutting down", Code.CANCELLED);
    
    pendingFutures.forEach(future -> 
        future.setException(exception.underlying));
    pendingFutures.clear();
  }
  
  @Override
  protected void doStart() {
    notifyStarted();
  }
  
  @Override
  protected void doStop() {
    cancelOutstandingPublishes();
    notifyStopped();
  }
  
  private ProducerRecord<byte[], byte[]> convertToKafkaRecord(PubSubMessage message) {
    // Extract key using configured key extractor
    byte[] key = message.getKey().isEmpty() ? null : message.getKey().toByteArray();
    
    // Create record with explicit partition
    ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
        topicName,
        partition.value(),  // Use explicit partition
        key,
        message.getData().toByteArray()
    );
    
    // Convert attributes to headers
    List<Header> headers = new ArrayList<>();
    message.getAttributesMap().forEach((k, v) -> 
        headers.add(new RecordHeader(k, v.toByteArray())));
    
    // Add event time as header if present
    if (message.hasEventTime()) {
      headers.add(new RecordHeader(
          "pubsublite.event_time",
          String.valueOf(message.getEventTime().getSeconds()).getBytes()
      ));
    }
    
    headers.forEach(record.headers()::add);
    
    return record;
  }
  
  private boolean isPermanentError(Exception e) {
    // Determine if error is permanent and should fail the publisher
    String message = e.getMessage();
    return message != null && (
        message.contains("InvalidTopicException") ||
        message.contains("AuthorizationException") ||
        message.contains("SecurityDisabledException")
    );
  }
}
```

### 5. SubscriberSettings Modifications

```java
// File: SubscriberSettings.java
// Changes: ADD 2 fields, MODIFY 1 method (identical pattern to PublisherSettings)

@AutoValue
public abstract class SubscriberSettings {
  // ============ EXISTING FIELDS (unchanged) ============
  // ... all existing fields ...
  
  // ============ NEW FIELDS ============
  abstract MessagingBackend messagingBackend();
  abstract Optional<Map<String, Object>> kafkaProperties();
  
  // ============ MODIFIED BUILDER ============
  public static Builder newBuilder() {
    return new AutoValue_SubscriberSettings.Builder()
        .setFramework(Framework.of("CLOUD_PUBSUB_SHIM"))
        .setPartitions(ImmutableList.of())
        .setCredentialsProvider(
            SubscriberServiceSettings.defaultCredentialsProviderBuilder().build())
        .setReassignmentHandler((before, after) -> {})
        .setMessagingBackend(MessagingBackend.PUBSUB_LITE); // NEW: default
  }
  
  // ============ MODIFIED METHOD ============
  PartitionSubscriberFactory getPartitionSubscriberFactory() {
    // NEW: Check backend
    if (messagingBackend() == MessagingBackend.MANAGED_KAFKA) {
      return new KafkaPartitionSubscriberFactory(this);
    }
    
    // EXISTING CODE (unchanged)
    SubscriberServiceClient client = newSubscriberServiceClient();
    CursorServiceClient cursorClient = newCursorServiceClient();
    // ... rest of existing implementation ...
  }
}
```

### 6. KafkaPartitionSubscriberFactory Implementation (New File)

```java
/*
 * Copyright 2024 Google LLC
 */
package com.google.cloud.pubsublite.cloudpubsub.internal;

import com.google.cloud.pubsublite.Partition;
import com.google.cloud.pubsublite.cloudpubsub.Subscriber;
import com.google.cloud.pubsublite.cloudpubsub.SubscriberSettings;
import com.google.cloud.pubsublite.internal.CheckedApiException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

/**
 * Factory for creating Kafka-based partition subscribers.
 */
class KafkaPartitionSubscriberFactory implements PartitionSubscriberFactory {
  private final SubscriberSettings settings;
  private final Properties baseConsumerProps;
  private final ConcurrentHashMap<Partition, Subscriber> subscribers;
  
  KafkaPartitionSubscriberFactory(SubscriberSettings settings) {
    this.settings = settings;
    this.subscribers = new ConcurrentHashMap<>();
    this.baseConsumerProps = createBaseConsumerProperties();
  }
  
  private Properties createBaseConsumerProperties() {
    Properties props = new Properties();
    
    // Configure connection (similar to publisher)
    configureKafkaConnection(props);
    configureAuthentication(props);
    
    // Consumer-specific settings
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
              ByteArrayDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
              ByteArrayDeserializer.class.getName());
    
    // Use subscription path as consumer group ID
    props.put(ConsumerConfig.GROUP_ID_CONFIG,
              settings.subscriptionPath().toString());
    
    // Auto-commit disabled (we manage offsets manually for ack/nack)
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    
    // Flow control alignment
    FlowControlSettings flowControl = settings.perPartitionFlowControlSettings();
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
              flowControl.messagesOutstanding());
    props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG,
              flowControl.bytesOutstanding());
    
    // Session timeout
    props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
    props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
    
    // Apply user overrides
    if (settings.kafkaProperties().isPresent()) {
      props.putAll(settings.kafkaProperties().get());
    }
    
    return props;
  }
  
  @Override
  public Subscriber newSubscriber(Partition partition) throws CheckedApiException {
    return subscribers.computeIfAbsent(partition, p -> {
      // Create partition-specific consumer
      Properties props = new Properties();
      props.putAll(baseConsumerProps);
      
      // Unique client ID per partition
      props.put(ConsumerConfig.CLIENT_ID_CONFIG,
                String.format("%s-partition-%d", 
                              settings.subscriptionPath().toString(),
                              partition.value()));
      
      KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props);
      
      return new KafkaPartitionSubscriber(
          consumer,
          settings,
          partition
      );
    });
  }
  
  @Override
  public void close() {
    subscribers.values().forEach(subscriber -> {
      try {
        subscriber.stopAsync().awaitTerminated();
      } catch (Exception e) {
        // Log but don't throw
      }
    });
  }
  
  // Similar helper methods as PublisherFactory...
  private void configureKafkaConnection(Properties props) {
    // Implementation similar to publisher factory
  }
  
  private void configureAuthentication(Properties props) {
    // Implementation similar to publisher factory
  }
}
```

## Resource Mapping Strategy

### Concept Mapping

| Pub/Sub Lite | Kafka | Notes |
|--------------|-------|-------|
| Topic | Topic | Direct mapping |
| Subscription | Consumer Group | Subscription name becomes group ID |
| Partition | Partition | Direct mapping |
| Message ID | Topic-Partition-Offset | Encoded as composite string |
| Ordering Key | Message Key | Used for partition routing |
| Attributes | Headers | Map to Kafka headers |
| Event Time | Header: pubsublite.event_time | Special header |
| Ack | Commit Offset | Commits offset on ack |
| Nack | Seek | Seeks to previous offset |
| Cursor | Consumer Offset | Managed by Kafka |

### Path Encoding Strategy

To maintain API compatibility while supporting Kafka, we encode Kafka cluster information in the existing path structures:

```java
// Pub/Sub Lite path (existing):
TopicPath.of(
    ProjectNumber.of(123456),
    CloudZone.of(CloudRegion.of("us-central1"), 'a'),
    TopicName.of("my-topic")
)

// Kafka path (new encoding):
TopicPath.of(
    ProjectNumber.of(123456),
    CloudZone.of("kafka-cluster1-us-central1"),  // Encode cluster in location
    TopicName.of("my-kafka-topic")
)
```

## Authentication and Security

### Google Cloud Managed Kafka Authentication

The implementation supports three authentication methods:

1. **OAUTHBEARER (Recommended)**
   - Uses Application Default Credentials
   - Automatically refreshes tokens
   - Integrates with Google Cloud IAM

2. **Service Account Key**
   - JSON key file authentication
   - Configured via credentials provider

3. **Access Token**
   - Short-lived token authentication
   - Suitable for temporary access

### Security Configuration

```java
// Example: Configure OAUTHBEARER authentication
PublisherSettings settings = PublisherSettings.newBuilder()
    .setTopicPath(topicPath)
    .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
    .setKafkaProperties(Map.of(
        "security.protocol", "SASL_SSL",
        "sasl.mechanism", "OAUTHBEARER",
        "ssl.endpoint.identification.algorithm", "https"
    ))
    .build();
```

## Testing Strategy

### 1. Unit Tests

```java
@Test
public void testKafkaBackendSelection() {
  PublisherSettings settings = PublisherSettings.newBuilder()
      .setTopicPath(topicPath)
      .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
      .build();
  
  // Verify Kafka factory is created
  PartitionPublisherFactory factory = settings.getPartitionPublisherFactory();
  assertThat(factory).isInstanceOf(KafkaPartitionPublisherFactory.class);
}

@Test
public void testDefaultBackendIsPubSubLite() {
  PublisherSettings settings = PublisherSettings.newBuilder()
      .setTopicPath(topicPath)
      .build();
  
  assertThat(settings.messagingBackend()).isEqualTo(MessagingBackend.PUBSUB_LITE);
}
```

### 2. Integration Tests

```java
public class KafkaIntegrationTest {
  @Rule
  public KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.0.1"));
  
  @Test
  public void testPublishAndSubscribe() throws Exception {
    // Configure for test Kafka
    PublisherSettings pubSettings = PublisherSettings.newBuilder()
        .setTopicPath(testTopicPath)
        .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
        .setKafkaProperties(Map.of(
            "bootstrap.servers", kafka.getBootstrapServers()
        ))
        .build();
    
    Publisher publisher = Publisher.create(pubSettings);
    publisher.startAsync().awaitRunning();
    
    // Publish message
    ApiFuture<String> future = publisher.publish(
        PubsubMessage.newBuilder()
            .setData(ByteString.copyFromUtf8("test message"))
            .build()
    );
    
    String messageId = future.get();
    assertThat(messageId).isNotEmpty();
    
    // Subscribe and verify
    // ... subscriber test code ...
  }
}
```

### 3. Compatibility Tests

```java
@Test
public void testExistingCodeUnaffected() {
  // This test ensures existing code works without modification
  Publisher publisher = Publisher.create(
      PublisherSettings.newBuilder()
          .setTopicPath(topicPath)
          .build()
  );
  
  // Verify it still uses Pub/Sub Lite
  // ... verification code ...
}
```

## Migration Guide

### For Existing Users

**No action required.** Existing code continues to work without any changes. The default backend remains Pub/Sub Lite.

### Migrating to Kafka Backend

#### Step 1: Add Kafka Configuration

```java
// Before (Pub/Sub Lite)
Publisher publisher = Publisher.create(
    PublisherSettings.newBuilder()
        .setTopicPath(topicPath)
        .setBatchingSettings(batchingSettings)
        .build()
);

// After (Kafka)
Publisher publisher = Publisher.create(
    PublisherSettings.newBuilder()
        .setTopicPath(topicPath)
        .setBatchingSettings(batchingSettings)
        .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
        .setKafkaProperties(Map.of(
            "bootstrap.servers", "cluster.region.managedkafka.gcp.cloud:9092"
        ))
        .build()
);
```

#### Step 2: Update Topic Paths

For Kafka topics, encode the cluster information in the location:

```java
// Pub/Sub Lite topic path
TopicPath pubsubLiteTopic = TopicPath.newBuilder()
    .setProject(ProjectNumber.of(123456))
    .setLocation(CloudZone.of(CloudRegion.of("us-central1"), 'a'))
    .setName(TopicName.of("my-topic"))
    .build();

// Kafka topic path
TopicPath kafkaTopic = TopicPath.newBuilder()
    .setProject(ProjectNumber.of(123456))
    .setLocation(CloudZone.of("kafka-mycluster-us-central1"))
    .setName(TopicName.of("my-kafka-topic"))
    .build();
```

#### Step 3: Handle Feature Differences

Some features have different semantics:

| Feature | Pub/Sub Lite | Kafka | Migration Notes |
|---------|--------------|-------|-----------------|
| Message Ordering | Per-partition ordering | Per-partition ordering | Same semantics |
| Deduplication | Built-in with message IDs | Idempotent producer | Enable idempotence |
| Retention | Time and size based | Time and size based | Configure similarly |
| Seek | Seek to timestamp/offset | Seek to timestamp/offset | Same operations |

### Environment-Based Configuration

```java
// Use environment variable for backend selection
MessagingBackend backend = MessagingBackend.valueOf(
    System.getenv().getOrDefault("MESSAGING_BACKEND", "PUBSUB_LITE")
);

PublisherSettings.Builder builder = PublisherSettings.newBuilder()
    .setTopicPath(topicPath)
    .setMessagingBackend(backend);

if (backend == MessagingBackend.MANAGED_KAFKA) {
  builder.setKafkaProperties(loadKafkaConfig());
}

Publisher publisher = Publisher.create(builder.build());
```

## Operational Considerations

### Monitoring

#### Metrics

Both backends expose metrics through the existing metrics framework:

```java
// Metrics are automatically tagged with backend type
Metrics.counter("publisher.messages.sent", 
    "backend", messagingBackend().name(),
    "topic", topicPath().toString()
).increment();
```

#### Logging

```java
// Backend type included in log context
logger.atInfo()
    .with("backend", messagingBackend())
    .with("topic", topicPath())
    .log("Publisher created");
```

### Performance Tuning

#### Pub/Sub Lite Performance

```java
// Optimized for Pub/Sub Lite
PublisherSettings.newBuilder()
    .setBatchingSettings(BatchingSettings.newBuilder()
        .setElementCountThreshold(1000L)
        .setRequestByteThreshold(1_000_000L)
        .setDelayThresholdDuration(Duration.ofMillis(50))
        .build())
    .build();
```

#### Kafka Performance

```java
// Optimized for Kafka
PublisherSettings.newBuilder()
    .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
    .setKafkaProperties(Map.of(
        "batch.size", 16384,
        "linger.ms", 10,
        "compression.type", "snappy",
        "buffer.memory", 33554432
    ))
    .build();
```

### Troubleshooting

#### Debug Logging

```java
// Enable debug logging for Kafka
System.setProperty("org.apache.kafka.clients.producer.loglevel", "DEBUG");
```

#### Common Issues

1. **Authentication Failures**
   - Verify credentials provider is configured
   - Check IAM permissions for Managed Kafka
   - Ensure OAUTHBEARER is properly configured

2. **Connection Issues**
   - Verify bootstrap servers are correct
   - Check network connectivity to Kafka cluster
   - Ensure SSL/TLS is properly configured

3. **Performance Issues**
   - Review batching settings
   - Check partition count and distribution
   - Monitor producer/consumer lag

## Risk Analysis and Mitigation

### Technical Risks

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| Breaking existing code | Very Low | Very High | No changes to existing code paths |
| Kafka client conflicts | Low | Medium | Shade dependencies if needed |
| Performance regression | Low | Medium | Extensive performance testing |
| Feature gaps | Medium | Low | Document limitations clearly |

### Mitigation Strategies

1. **Feature Flag Protection**

```java
// Add system property to control feature availability
if (messagingBackend() == MessagingBackend.MANAGED_KAFKA) {
  if (!"true".equals(System.getProperty("pubsublite.kafka.enabled"))) {
    throw new UnsupportedOperationException(
        "Kafka backend is experimental. Enable with -Dpubsublite.kafka.enabled=true");
  }
}
```

2. **Gradual Rollout**

- Phase 1: Internal testing only
- Phase 2: Beta users with feature flag
- Phase 3: GA with full support

3. **Rollback Plan**

Simply don't set the backend to MANAGED_KAFKA. All existing code continues to work with Pub/Sub Lite.

## Implementation Timeline

### Week 1: Foundation
- [ ] Add MessagingBackend enum
- [ ] Modify PublisherSettings and SubscriberSettings
- [ ] Add Kafka dependencies to pom.xml
- [ ] Create unit test framework

### Week 2: Publisher Implementation
- [ ] Implement KafkaPartitionPublisherFactory
- [ ] Implement KafkaPartitionPublisher
- [ ] Add publisher unit tests
- [ ] Integration tests with embedded Kafka

### Week 3: Subscriber Implementation
- [ ] Implement KafkaPartitionSubscriberFactory
- [ ] Implement KafkaPartitionSubscriber
- [ ] Add subscriber unit tests
- [ ] End-to-end integration tests

### Week 4: Authentication & Security
- [ ] Implement OAUTHBEARER authentication
- [ ] Add service account key support
- [ ] Security testing
- [ ] Documentation

### Week 5: Testing & Documentation
- [ ] Comprehensive integration tests
- [ ] Performance benchmarks
- [ ] User documentation
- [ ] Migration guide

### Week 6: Release Preparation
- [ ] Code review
- [ ] Security review
- [ ] Performance validation
- [ ] Beta release

## Success Metrics

1. **Compatibility**: 100% of existing tests pass without modification
2. **Performance**: Kafka backend within 10% of native Kafka client performance
3. **Adoption**: Successfully used by 3+ pilot customers
4. **Quality**: >90% code coverage on new code
5. **Stability**: Zero critical bugs in first month after release

## Conclusion

This design provides a minimal, low-risk approach to adding Kafka support to the Java Pub/Sub Lite client. By modifying only 2 existing files with approximately 20 lines of changes, we maintain complete backward compatibility while enabling users to leverage Google Cloud Managed Service for Apache Kafka. The implementation follows existing patterns in the codebase and isolates all Kafka-specific logic in new files, making it easy to maintain, test, and potentially remove if needed.