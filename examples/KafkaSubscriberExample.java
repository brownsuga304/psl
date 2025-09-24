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
import com.google.cloud.pubsublite.cloudpubsub.MessagingBackend;
import com.google.cloud.pubsublite.cloudpubsub.Subscriber;
import com.google.cloud.pubsublite.cloudpubsub.SubscriberSettings;
import com.google.pubsub.v1.PubsubMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Example of using the Pub/Sub Lite Subscriber with a Kafka backend.
 * This demonstrates consuming messages from a Kafka topic.
 */
public class KafkaSubscriberExample {

  public static void main(String[] args) throws Exception {
    // Replace these with your actual values
    String projectId = "your-project-id";
    String region = "us-central1";
    String subscriptionName = "your-subscription";
    String kafkaBootstrapServers = "localhost:9092"; // Or your Kafka cluster

    SubscriptionPath subscriptionPath = SubscriptionPath.newBuilder()
        .setProject(ProjectId.of(projectId))
        .setLocation(CloudRegion.of(region))
        .setName(SubscriptionName.of(subscriptionName))
        .build();

    // Kafka-specific properties
    Map<String, Object> kafkaProperties = new HashMap<>();
    kafkaProperties.put("bootstrap.servers", kafkaBootstrapServers);

    // For Google Managed Kafka, add authentication
    // kafkaProperties.put("security.protocol", "SASL_SSL");
    // kafkaProperties.put("sasl.mechanism", "OAUTHBEARER");
    // kafkaProperties.put("sasl.login.callback.handler.class",
    //     "com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler");
    // kafkaProperties.put("sasl.jaas.config",
    //     "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;");

    // Message receiver that handles incoming messages
    MessageReceiver receiver = new MessageReceiver() {
      @Override
      public void receiveMessage(PubsubMessage message, AckReplyConsumer consumer) {
        System.out.println("Received message ID: " + message.getMessageId());
        System.out.println("Data: " + message.getData().toStringUtf8());
        System.out.println("Attributes: " + message.getAttributesMap());

        // Process the message...

        // Acknowledge the message when done
        consumer.ack();

        // Or nack() to reprocess later:
        // consumer.nack();
      }
    };

    // Flow control settings
    FlowControlSettings flowControlSettings = FlowControlSettings.builder()
        .setBytesOutstanding(10 * 1024 * 1024L) // 10 MB
        .setMessagesOutstanding(1000L)
        .build();

    // Create subscriber settings for Kafka
    SubscriberSettings settings = SubscriberSettings.newBuilder()
        .setSubscriptionPath(subscriptionPath)
        .setReceiver(receiver)
        .setPerPartitionFlowControlSettings(flowControlSettings)
        .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
        .setKafkaProperties(kafkaProperties)
        .build();

    Subscriber subscriber = null;
    try {
      subscriber = Subscriber.create(settings);

      // Start the subscriber
      subscriber.startAsync().awaitRunning();
      System.out.println("Listening for messages from Kafka topic...");

      // Keep the main thread alive while the subscriber processes messages
      // In a real application, you would have proper lifecycle management
      Thread.sleep(60000); // Listen for 60 seconds

    } catch (Exception e) {
      System.err.println("Subscriber failed: " + e.getMessage());
      e.printStackTrace();
    } finally {
      if (subscriber != null) {
        try {
          subscriber.stopAsync().awaitTerminated(30, TimeUnit.SECONDS);
          System.out.println("Subscriber stopped");
        } catch (TimeoutException e) {
          System.err.println("Failed to stop subscriber gracefully");
        }
      }
    }
  }
}