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

import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsublite.CloudRegion;
import com.google.cloud.pubsublite.ProjectId;
import com.google.cloud.pubsublite.SubscriptionName;
import com.google.cloud.pubsublite.SubscriptionPath;
import com.google.cloud.pubsublite.cloudpubsub.FlowControlSettings;
import com.google.cloud.pubsublite.cloudpubsub.GmkUtils;
import com.google.cloud.pubsublite.cloudpubsub.MessagingBackend;
import com.google.cloud.pubsublite.cloudpubsub.Subscriber;
import com.google.cloud.pubsublite.cloudpubsub.SubscriberSettings;
import com.google.pubsub.v1.PubsubMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example of consuming messages from Google Managed Kafka (GMK) using
 * the Pub/Sub Lite subscriber with Kafka backend.
 */
public class GmkSubscriberExample {

  public static void main(String[] args) throws Exception {
    System.out.println("🚀 Google Managed Kafka Subscriber Example");
    System.out.println("==========================================");

    // GMK Cluster Details - REPLACE WITH YOUR ACTUAL VALUES
    String projectId = "arkam-test";
    String region = "us-central1";
    String clusterId = "test";
    String topicName = "my-gmk-topic";
    String subscriptionName = topicName + "-sub"; // Using topic name as base

    // Build GMK bootstrap server URL
    String bootstrapServers = GmkUtils.buildGmkBootstrapServer(projectId, region, clusterId);
    System.out.println("📡 Bootstrap Servers: " + bootstrapServers);

    // Create subscription path (using topic name as subscription identifier)
    SubscriptionPath subscriptionPath = SubscriptionPath.newBuilder()
        .setProject(ProjectId.of(projectId))
        .setLocation(CloudRegion.of(region))
        .setName(SubscriptionName.of(subscriptionName))
        .build();

    // Configure Kafka properties for GMK
    Map<String, Object> kafkaProperties = new HashMap<>();
    kafkaProperties.put("bootstrap.servers", bootstrapServers);
    kafkaProperties.put("security.protocol", "SASL_SSL");
    kafkaProperties.put("sasl.mechanism", "OAUTHBEARER");
    kafkaProperties.put("sasl.login.callback.handler.class",
        "com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler");
    kafkaProperties.put("sasl.jaas.config",
        "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;");

    // Validate configuration
    System.out.println("🔍 Validating GMK configuration...");
    GmkUtils.ValidationResult validation = GmkUtils.validateGmkConfiguration(kafkaProperties);
    System.out.println("   " + validation);

    if (!validation.isSuccess() && validation.getLevel() == GmkUtils.ValidationResult.Level.ERROR) {
      System.err.println("❌ Configuration validation failed. Please fix the issues above.");
      System.exit(1);
    }

    // Message tracking
    AtomicInteger messageCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(10); // Wait for 10 messages

    // Message receiver
    MessageReceiver receiver = new MessageReceiver() {
      @Override
      public void receiveMessage(PubsubMessage message, AckReplyConsumer consumer) {
        int count = messageCount.incrementAndGet();

        System.out.println("\n📨 Message #" + count);
        System.out.println("   ID: " + message.getMessageId());
        System.out.println("   Data: " + message.getData().toStringUtf8());

        if (!message.getAttributesMap().isEmpty()) {
          System.out.println("   Attributes:");
          message.getAttributesMap().forEach((key, value) ->
              System.out.println("      " + key + ": " + value));
        }

        // Acknowledge the message
        consumer.ack();

        latch.countDown();
      }
    };

    // Flow control settings
    FlowControlSettings flowControlSettings = FlowControlSettings.builder()
        .setBytesOutstanding(10 * 1024 * 1024L) // 10 MB
        .setMessagesOutstanding(100L)
        .build();

    // Create subscriber settings
    SubscriberSettings settings = SubscriberSettings.newBuilder()
        .setSubscriptionPath(subscriptionPath)
        .setReceiver(receiver)
        .setPerPartitionFlowControlSettings(flowControlSettings)
        .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
        .setKafkaProperties(kafkaProperties)
        .build();

    System.out.println("✅ Settings configured for Google Managed Kafka backend");

    Subscriber subscriber = null;
    try {
      System.out.println("🔧 Creating Kafka subscriber...");
      subscriber = Subscriber.create(settings);

      System.out.println("▶️  Starting subscriber...");
      subscriber.startAsync().awaitRunning();

      System.out.println("✅ Subscriber started successfully!");
      System.out.println("👂 Listening for messages from topic: " + topicName);
      System.out.println("   (Waiting for up to 10 messages or 2 minutes...)\n");

      // Wait for messages or timeout
      boolean receivedAll = latch.await(2, TimeUnit.MINUTES);

      if (receivedAll) {
        System.out.println("\n🎉 Received all 10 messages!");
      } else {
        System.out.println("\n⏱️  Timeout reached. Received " + messageCount.get() + " messages.");
      }

    } catch (Exception e) {
      System.err.println("\n❌ Error: " + e.getMessage());

      if (e.getMessage() != null && e.getMessage().contains("Failed to resolve Kafka bootstrap servers")) {
        System.err.println("\n🔧 Troubleshooting steps:");
        System.err.println("1. Verify your GMK cluster exists:");
        System.err.println("   gcloud managed-kafka clusters describe " + clusterId + " \\");
        System.err.println("       --location=" + region + " --project=" + projectId);
        System.err.println("");
        System.err.println("2. Ensure the topic exists:");
        System.err.println("   gcloud managed-kafka topics list \\");
        System.err.println("       --cluster=" + clusterId + " --location=" + region + " --project=" + projectId);
        System.err.println("");
        System.err.println("3. Check your network connectivity to *.cloud.goog domains");
      }

      throw e;
    } finally {
      if (subscriber != null) {
        try {
          System.out.println("\n⏹️  Stopping subscriber...");
          subscriber.stopAsync().awaitTerminated(10, TimeUnit.SECONDS);
          System.out.println("✅ Subscriber stopped");
        } catch (Exception e) {
          System.err.println("⚠️  Error stopping subscriber: " + e.getMessage());
        }
      }
    }

    System.out.println("\n📊 Summary: Processed " + messageCount.get() + " messages");
  }
}