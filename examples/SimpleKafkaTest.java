/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.cloud.pubsublite.cloudpubsub;

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

/**
 * Simple test to verify Kafka publishing works with local Docker Compose setup
 */
public class SimpleKafkaTest {

    public static void main(String[] args) throws Exception {
        System.out.println("🧪 Testing Kafka publishing functionality...");

        // Create topic path
        TopicPath topicPath = TopicPath.newBuilder()
            .setProject(ProjectNumber.of(123456L))
            .setLocation(CloudZone.of(CloudRegion.of("us-central1"), 'a'))
            .setName(TopicName.of("my-kafka-topic"))
            .build();

        // Configure for local Kafka
        Map<String, Object> kafkaProperties = new HashMap<>();
        kafkaProperties.put("bootstrap.servers", "localhost:9092");
        kafkaProperties.put("acks", "all");
        kafkaProperties.put("retries", 3);

        // Create publisher settings
        PublisherSettings settings = PublisherSettings.newBuilder()
            .setTopicPath(topicPath)
            .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
            .setKafkaProperties(kafkaProperties)
            .build();

        System.out.println("✅ Settings configured for Kafka backend");

        // Create and start publisher
        Publisher publisher = null;
        try {
            publisher = Publisher.create(settings);
            publisher.startAsync().awaitRunning();
            System.out.println("✅ Publisher started successfully!");

            // Publish a test message
            PubsubMessage message = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("Hello from Kafka integration test!"))
                .putAttributes("source", "simple-test")
                .putAttributes("timestamp", String.valueOf(System.currentTimeMillis()))
                .setOrderingKey("test-key")
                .build();

            System.out.println("📤 Publishing test message...");
            ApiFuture<String> future = publisher.publish(message);
            String messageId = future.get();

            System.out.println("🎉 Message published successfully!");
            System.out.println("📋 Message ID: " + messageId);
            System.out.println("");
            System.out.println("🔍 To verify the message was received:");
            System.out.println("docker-compose exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic my-kafka-topic --from-beginning");

        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            if (publisher != null) {
                publisher.stopAsync().awaitTerminated();
                System.out.println("✅ Publisher stopped");
            }
        }
    }
}