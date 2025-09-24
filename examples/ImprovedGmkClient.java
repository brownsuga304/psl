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

import com.google.api.core.ApiFuture;
import com.google.cloud.pubsublite.CloudRegion;
import com.google.cloud.pubsublite.ProjectId;
import com.google.cloud.pubsublite.TopicName;
import com.google.cloud.pubsublite.TopicPath;
import com.google.cloud.pubsublite.cloudpubsub.GmkUtils;
import com.google.cloud.pubsublite.cloudpubsub.MessagingBackend;
import com.google.cloud.pubsublite.cloudpubsub.Publisher;
import com.google.cloud.pubsublite.cloudpubsub.PublisherSettings;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import java.util.HashMap;
import java.util.Map;

/**
 * Improved Google Managed Kafka client with better error handling and validation.
 */
public class ImprovedGmkClient {

  public static void main(String[] args) throws Exception {
    System.out.println("🧪 Testing Google Managed Kafka publishing functionality...");

    // GMK Cluster Details - REPLACE WITH YOUR ACTUAL VALUES
    String projectId = "arkam-test";
    String region = "us-central1";
    String clusterId = "test";
    String topicName = "my-gmk-topic";

    // Construct the GMK bootstrap server address using utility
    String bootstrapServers = GmkUtils.buildGmkBootstrapServer(projectId, region, clusterId);
    System.out.println("🌐 Bootstrap Servers: " + bootstrapServers);

    // TopicPath for the library
    TopicPath topicPath = TopicPath.newBuilder()
        .setProject(ProjectId.of(projectId))
        .setLocation(CloudRegion.of(region))
        .setName(TopicName.of(topicName))
        .build();

    // Configure Kafka properties for GMK
    Map<String, Object> kafkaProperties = new HashMap<>();
    kafkaProperties.put("bootstrap.servers", bootstrapServers);
    kafkaProperties.put("security.protocol", "SASL_SSL");
    kafkaProperties.put("sasl.mechanism", "OAUTHBEARER");
    kafkaProperties.put("sasl.login.callback.handler.class", "com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler");
    kafkaProperties.put("sasl.jaas.config", "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;");
    
    // Optional: Add these for better reliability
    // kafkaProperties.put("acks", "all");
    // kafkaProperties.put("retries", 3);

    // Validate configuration before proceeding
    System.out.println("🔍 Validating GMK configuration...");
    GmkUtils.ValidationResult validation = GmkUtils.validateGmkConfiguration(kafkaProperties);
    System.out.println("   " + validation);
    
    if (!validation.isSuccess() && validation.getLevel() == GmkUtils.ValidationResult.Level.ERROR) {
      System.err.println("❌ Configuration validation failed. Please fix the issues above before proceeding.");
      System.exit(1);
    }

    // Create publisher settings
    PublisherSettings settings = PublisherSettings.newBuilder()
        .setTopicPath(topicPath)
        .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
        .setKafkaProperties(kafkaProperties)
        .build();

    System.out.println("✅ Settings configured for Google Managed Kafka backend");

    Publisher publisher = null;
    try {
      System.out.println("🔧 Creating Kafka publisher...");
      publisher = Publisher.create(settings);
      
      System.out.println("🚀 Starting publisher...");
      publisher.startAsync().awaitRunning();
      System.out.println("✅ Publisher started successfully!");

      PubsubMessage message = PubsubMessage.newBuilder()
          .setData(ByteString.copyFromUtf8("Hello from GMK test! " + System.currentTimeMillis()))
          .putAttributes("source", "improved-gmk-test")
          .putAttributes("client", "pubsub-lite-kafka-integration")
          .build();

      System.out.println("📤 Publishing test message to topic: " + topicName);
      ApiFuture<String> future = publisher.publish(message);
      String messageId = future.get();

      System.out.println("🎉 Message published successfully!");
      System.out.println("📋 Message ID: " + messageId);

    } catch (Exception e) {
      System.err.println("❌ Error: " + e.getMessage());
      
      // Provide specific guidance based on error type
      if (e.getMessage().contains("Failed to resolve Kafka bootstrap servers")) {
        System.err.println("\n🔧 Troubleshooting steps:");
        System.err.println("1. Verify your GMK cluster exists:");
        System.err.println("   gcloud managed-kafka clusters describe " + clusterId + " \\");
        System.err.println("       --location=" + region + " --project=" + projectId);
        System.err.println("");
        System.err.println("2. List all clusters if unsure:");
        System.err.println("   gcloud managed-kafka clusters list --location=" + region + " --project=" + projectId);
        System.err.println("");
        System.err.println("3. Check your network connectivity to *.cloud.goog domains");
      }
      
      throw e;
    } finally {
      if (publisher != null) {
        try {
          publisher.stopAsync().awaitTerminated();
          System.out.println("✅ Publisher stopped");
        } catch (Exception e) {
          System.err.println("⚠️  Error stopping publisher: " + e.getMessage());
        }
      }
    }
  }
}