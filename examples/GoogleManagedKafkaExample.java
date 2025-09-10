/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

import com.google.api.core.ApiFuture;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.pubsublite.CloudRegion;
import com.google.cloud.pubsublite.CloudZone;
import com.google.cloud.pubsublite.ProjectNumber;
import com.google.cloud.pubsublite.TopicName;
import com.google.cloud.pubsublite.TopicPath;
import com.google.cloud.pubsublite.cloudpubsub.MessagingBackend;
import com.google.cloud.pubsublite.cloudpubsub.Publisher;
import com.google.cloud.pubsublite.cloudpubsub.PublisherSettings;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Example showing how to publish to Google Cloud Managed Service for Apache Kafka
 * 
 * Prerequisites:
 * 1. Create a Managed Kafka cluster in Google Cloud Console
 * 2. Create a topic in the cluster
 * 3. Set up authentication (service account or gcloud auth)
 * 4. Update the configuration below with your cluster details
 */
public class GoogleManagedKafkaExample {

  public static void main(String[] args) throws Exception {
    // 🔧 CONFIGURATION - Update these values for your setup
    String projectNumber = "YOUR_PROJECT_NUMBER";  // e.g., "123456789012"
    String region = "us-central1";                 // Your cluster region
    String clusterName = "your-cluster-name";      // Your Kafka cluster name
    String topicName = "your-topic-name";          // Your Kafka topic name
    
    // Google Managed Kafka cluster endpoint format
    String bootstrapServers = String.format("%s.%s.managedkafka.gcp.cloud:9092", 
                                           clusterName, region);
    
    System.out.println("🔗 Connecting to Google Managed Kafka:");
    System.out.println("   Cluster: " + bootstrapServers);
    System.out.println("   Topic: " + topicName);
    
    // Create topic path (encoded with cluster info)
    TopicPath topicPath = TopicPath.of(
        ProjectNumber.of(Long.parseLong(projectNumber)),
        CloudZone.of(CloudRegion.of(region), 'a'), // Zone not used for Kafka but required
        TopicName.of(topicName)
    );

    // Configure Kafka properties for Google Managed Kafka
    Map<String, Object> kafkaProperties = createManagedKafkaProperties(bootstrapServers);
    
    // Create publisher settings
    PublisherSettings settings = PublisherSettings.newBuilder()
        .setTopicPath(topicPath)
        .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
        .setKafkaProperties(kafkaProperties)
        .build();

    // Create and start publisher
    try (AutoCloseablePublisher publisher = new AutoCloseablePublisher(settings)) {
      System.out.println("✅ Publisher started successfully!");
      
      // Publish test messages
      publishTestMessages(publisher);
      
      System.out.println("🎉 All messages published successfully!");
      
    } catch (Exception e) {
      System.err.println("❌ Error: " + e.getMessage());
      e.printStackTrace();
      throw e;
    }
  }
  
  private static Map<String, Object> createManagedKafkaProperties(String bootstrapServers) {
    Map<String, Object> props = new HashMap<>();
    
    // Connection settings
    props.put("bootstrap.servers", bootstrapServers);
    
    // Security settings for Google Managed Kafka
    props.put("security.protocol", "SASL_SSL");
    props.put("sasl.mechanism", "OAUTHBEARER");
    
    // Google Cloud authentication
    props.put("sasl.jaas.config", 
        "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;");
    
    // Performance and reliability settings
    props.put("acks", "all");                    // Wait for all replicas
    props.put("retries", Integer.MAX_VALUE);     // Retry indefinitely
    props.put("max.in.flight.requests.per.connection", 5);
    props.put("enable.idempotence", true);       // Exactly-once semantics
    props.put("compression.type", "snappy");     // Compress messages
    
    // Batching for better throughput
    props.put("batch.size", 65536);              // 64KB batches
    props.put("linger.ms", 50);                  // Wait 50ms to batch
    props.put("buffer.memory", 33554432);        // 32MB send buffer
    
    return props;
  }
  
  private static void publishTestMessages(AutoCloseablePublisher publisher) throws Exception {
    System.out.println("📤 Publishing test messages...");
    
    for (int i = 0; i < 5; i++) {
      // Create message with rich metadata
      PubsubMessage message = PubsubMessage.newBuilder()
          .setData(ByteString.copyFromUtf8(
              String.format("Hello from Google Managed Kafka! Message #%d", i)))
          .putAttributes("source", "java-pubsublite-client")
          .putAttributes("messageId", String.valueOf(i))
          .putAttributes("timestamp", String.valueOf(System.currentTimeMillis()))
          .putAttributes("environment", "production")
          .setOrderingKey("test-key-" + (i % 2)) // Distribute across 2 partitions
          .build();

      // Publish message
      ApiFuture<String> future = publisher.getPublisher().publish(message);
      
      try {
        String messageId = future.get();
        System.out.printf("✅ Published message %d: %s%n", i, messageId);
      } catch (Exception e) {
        System.err.printf("❌ Failed to publish message %d: %s%n", i, e.getMessage());
        throw e;
      }
    }
    
    // Ensure all messages are sent
    publisher.getPublisher().flush();
  }
  
  // Helper class for auto-closing the publisher
  private static class AutoCloseablePublisher implements AutoCloseable {
    private final Publisher publisher;
    
    public AutoCloseablePublisher(PublisherSettings settings) throws IOException {
      this.publisher = Publisher.create(settings);
      this.publisher.startAsync().awaitRunning();
    }
    
    public Publisher getPublisher() {
      return publisher;
    }
    
    @Override
    public void close() throws Exception {
      if (publisher != null) {
        publisher.stopAsync().awaitTerminated();
      }
    }
  }
  
  // Helper method to verify authentication setup
  private static void verifyAuthentication() throws IOException {
    try {
      GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
      System.out.println("✅ Authentication: Using Application Default Credentials");
      System.out.println("   Account: " + credentials.toString());
    } catch (IOException e) {
      System.err.println("❌ Authentication Error: " + e.getMessage());
      System.err.println("");
      System.err.println("To set up authentication:");
      System.err.println("1. Install gcloud CLI: https://cloud.google.com/sdk/docs/install");
      System.err.println("2. Run: gcloud auth application-default login");
      System.err.println("3. Or set GOOGLE_APPLICATION_CREDENTIALS environment variable");
      throw e;
    }
  }
}