/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

import com.google.api.core.ApiFuture;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class KafkaPublisherExample {

  public static void main(String[] args) throws Exception {
    // Configure for your setup
    String projectNumber = "123456789";
    String location = "us-central1-a"; 
    String topicName = "my-kafka-topic";
    String kafkaBootstrapServers = "localhost:9092"; // Change to your Kafka cluster
    
    // Create topic path
    TopicPath topicPath = TopicPath.of(
        ProjectNumber.of(Long.parseLong(projectNumber)),
        CloudZone.of(CloudRegion.of("us-central1"), 'a'),
        TopicName.of(topicName)
    );

    // Configure Kafka properties
    Map<String, Object> kafkaProperties = new HashMap<>();
    kafkaProperties.put("bootstrap.servers", kafkaBootstrapServers);
    kafkaProperties.put("acks", "all");
    kafkaProperties.put("retries", 3);
    kafkaProperties.put("compression.type", "snappy");
    
    // Create publisher settings for Kafka
    PublisherSettings settings = PublisherSettings.newBuilder()
        .setTopicPath(topicPath)
        .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
        .setKafkaProperties(kafkaProperties)
        .build();

    // Create and start publisher
    Publisher publisher = Publisher.create(settings);
    publisher.startAsync().awaitRunning();
    
    System.out.println("Publisher started. Publishing messages to Kafka...");

    try {
      // Publish some test messages
      for (int i = 0; i < 10; i++) {
        PubsubMessage message = PubsubMessage.newBuilder()
            .setData(ByteString.copyFromUtf8("Hello Kafka message " + i))
            .putAttributes("source", "java-pubsublite-client")
            .putAttributes("messageNumber", String.valueOf(i))
            .build();

        ApiFuture<String> future = publisher.publish(message);
        
        try {
          String messageId = future.get();
          System.out.printf("Published message %d with ID: %s%n", i, messageId);
        } catch (ExecutionException e) {
          System.err.printf("Failed to publish message %d: %s%n", i, e.getCause());
        }
      }
      
      // Flush remaining messages
      publisher.flush();
      System.out.println("All messages published successfully!");
      
    } finally {
      // Clean shutdown
      publisher.stopAsync().awaitTerminated();
      System.out.println("Publisher stopped.");
    }
  }
}