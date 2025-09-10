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

import static com.google.common.truth.Truth.assertThat;

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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class KafkaLiveTest {

  @Test
  public void testPublishToLiveKafka() throws Exception {
    // Skip if Kafka is not available
    if (!isKafkaAvailable()) {
      System.out.println("⏭️  Skipping live Kafka test - Kafka not available on localhost:9092");
      return;
    }

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

    Publisher publisher = null;
    try {
      publisher = Publisher.create(settings);
      publisher.startAsync().awaitRunning();
      System.out.println("✅ Publisher started successfully!");

      // Publish a test message
      PubsubMessage message = PubsubMessage.newBuilder()
          .setData(ByteString.copyFromUtf8("Hello from Java Pub/Sub Lite -> Kafka integration!"))
          .putAttributes("source", "java-pubsublite-client")
          .putAttributes("test-type", "live-integration-test")
          .putAttributes("timestamp", String.valueOf(System.currentTimeMillis()))
          .setOrderingKey("integration-test-key")
          .build();

      System.out.println("📤 Publishing test message...");
      ApiFuture<String> future = publisher.publish(message);
      String messageId = future.get(30, TimeUnit.SECONDS);

      // Verify message ID format (partition:offset)
      assertThat(messageId).isNotEmpty();
      assertThat(messageId).contains(":");
      
      System.out.println("🎉 Message published successfully!");
      System.out.println("📋 Message ID: " + messageId);
      System.out.println("");
      System.out.println("🔍 To verify the message was received:");
      System.out.println("docker-compose exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic my-kafka-topic --from-beginning --property print.headers=true");

    } finally {
      if (publisher != null) {
        publisher.stopAsync().awaitTerminated();
        System.out.println("✅ Publisher stopped");
      }
    }
  }

  private boolean isKafkaAvailable() {
    try {
      java.net.Socket socket = new java.net.Socket();
      socket.connect(new java.net.InetSocketAddress("localhost", 9092), 1000);
      socket.close();
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}