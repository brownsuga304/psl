/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.pubsublite.cloudpubsub;

import com.google.api.core.ApiFuture;
import com.google.cloud.pubsublite.CloudRegion;
import com.google.cloud.pubsublite.CloudZone;
import com.google.cloud.pubsublite.ProjectNumber;
import com.google.cloud.pubsublite.TopicName;
import com.google.cloud.pubsublite.TopicPath;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Simple test for Google Managed Kafka publisher.
 *
 * <p>Run with: ./test-managed-kafka.sh BOOTSTRAP_SERVER TOPIC_NAME
 *
 * <p>Example: ./test-managed-kafka.sh
 * bootstrap.test-kafka.us-central1.managedkafka.myproject.cloud.goog:9092 test-topic
 */
public class ManagedKafkaTest {

  public static void main(String[] args) throws Exception {
    // Get config from args
    String bootstrapServers = args.length > 0 ? args[0] : null;
    String topicName = args.length > 1 ? args[1] : null;

    if (bootstrapServers == null || topicName == null) {
      System.out.println("Usage: ManagedKafkaTest <BOOTSTRAP_SERVER> <TOPIC_NAME>");
      System.out.println();
      System.out.println("Example:");
      System.out.println(
          "  ./test-managed-kafka.sh bootstrap.test-kafka.us-central1.managedkafka.myproject.cloud.goog:9092 test-topic");
      System.exit(1);
    }

    System.out.println("=== Google Managed Kafka Publisher Test ===");
    System.out.println();
    System.out.println("Configuration:");
    System.out.println("  Bootstrap:  " + bootstrapServers);
    System.out.println("  Topic:      " + topicName);
    System.out.println();

    // Build topic path (project/zone not used for Kafka, but required by API)
    TopicPath topicPath =
        TopicPath.newBuilder()
            .setProject(ProjectNumber.of(1L))
            .setLocation(CloudZone.of(CloudRegion.of("us-central1"), 'a'))
            .setName(TopicName.of(topicName))
            .build();

    // Kafka properties for Google Managed Kafka
    Map<String, Object> kafkaProps = new HashMap<>();
    kafkaProps.put("bootstrap.servers", bootstrapServers);
    kafkaProps.put("security.protocol", "SASL_SSL");
    kafkaProps.put("sasl.mechanism", "OAUTHBEARER");
    kafkaProps.put(
        "sasl.login.callback.handler.class",
        "com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler");
    kafkaProps.put(
        "sasl.jaas.config",
        "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;");

    // Reliability settings
    kafkaProps.put("acks", "all");
    kafkaProps.put("retries", 3);
    kafkaProps.put("request.timeout.ms", 30000);

    // Build publisher settings
    PublisherSettings settings =
        PublisherSettings.newBuilder()
            .setTopicPath(topicPath)
            .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
            .setKafkaProperties(kafkaProps)
            .build();

    System.out.println("Creating publisher...");
    Publisher publisher = Publisher.create(settings);

    try {
      System.out.println("Starting publisher...");
      publisher.startAsync().awaitRunning();
      System.out.println("Publisher started successfully!");
      System.out.println();

      // Send test messages
      for (int i = 1; i <= 3; i++) {
        String payload = "Test message #" + i + " at " + System.currentTimeMillis();

        PubsubMessage message =
            PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(payload))
                .putAttributes("test", "true")
                .putAttributes("index", String.valueOf(i))
                .build();

        System.out.println("Publishing: " + payload);
        ApiFuture<String> future = publisher.publish(message);

        String messageId = future.get(30, TimeUnit.SECONDS);
        System.out.println("  -> Message ID: " + messageId);
      }

      System.out.println();
      System.out.println("=== TEST PASSED ===");
      System.out.println("All 3 messages published successfully!");

    } catch (Exception e) {
      System.out.println();
      System.out.println("=== TEST FAILED ===");
      System.out.println("Error: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    } finally {
      System.out.println();
      System.out.println("Stopping publisher...");
      publisher.stopAsync().awaitTerminated();
      System.out.println("Done.");
    }
  }
}
