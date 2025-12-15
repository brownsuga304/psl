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
import com.google.cloud.pubsublite.Offset;
import com.google.cloud.pubsublite.Partition;
import com.google.cloud.pubsublite.ProjectNumber;
import com.google.cloud.pubsublite.SubscriptionName;
import com.google.cloud.pubsublite.SubscriptionPath;
import com.google.cloud.pubsublite.TopicName;
import com.google.cloud.pubsublite.TopicPath;
import com.google.cloud.pubsublite.internal.KafkaCursorClient;
import com.google.cloud.pubsublite.internal.KafkaTopicStatsClient;
import com.google.cloud.pubsublite.internal.KafkaTopicStatsClient.BacklogInfo;
import com.google.cloud.pubsublite.proto.ComputeMessageStatsResponse;
import com.google.cloud.pubsublite.proto.Cursor;
import com.google.cloud.pubsublite.proto.PartitionCursor;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Test for Kafka cursor and topic stats clients.
 *
 * <p>This test demonstrates the cursor and topic stats functionality:
 * - Committing and reading offsets (cursor management)
 * - Getting earliest/latest offsets (topic stats)
 * - Computing backlog estimates
 *
 * <p>Run with:
 *   ./test-managed-kafka-stats.sh BOOTSTRAP_SERVER TOPIC_NAME [NUM_MESSAGES]
 *
 * <p>Example:
 *   ./test-managed-kafka-stats.sh bootstrap.test-kafka.us-central1.managedkafka.myproject.cloud.goog:9092 test-topic 10
 */
public class ManagedKafkaStatsTest {

  public static void main(String[] args) throws Exception {
    // Get config from args
    String bootstrapServers = args.length > 0 ? args[0] : null;
    String topicName = args.length > 1 ? args[1] : null;
    int numMessages = args.length > 2 ? Integer.parseInt(args[2]) : 10;

    if (bootstrapServers == null || topicName == null) {
      System.out.println("Usage: ManagedKafkaStatsTest <BOOTSTRAP_SERVER> <TOPIC_NAME> [NUM_MESSAGES]");
      System.out.println();
      System.out.println("Example:");
      System.out.println("  ./test-managed-kafka-stats.sh bootstrap.test-kafka.us-central1.managedkafka.myproject.cloud.goog:9092 test-topic 10");
      System.exit(1);
    }

    System.out.println("=== Google Managed Kafka Cursor & Stats Test ===");
    System.out.println();
    System.out.println("Configuration:");
    System.out.println("  Bootstrap:    " + bootstrapServers);
    System.out.println("  Topic:        " + topicName);
    System.out.println("  NumMessages:  " + numMessages);
    System.out.println();

    // Build paths
    CloudRegion region = CloudRegion.of("us-central1");
    TopicPath topicPath = TopicPath.newBuilder()
        .setProject(ProjectNumber.of(1L))
        .setLocation(CloudZone.of(region, 'a'))
        .setName(TopicName.of(topicName))
        .build();

    SubscriptionPath subscriptionPath = SubscriptionPath.newBuilder()
        .setProject(ProjectNumber.of(1L))
        .setLocation(CloudZone.of(region, 'a'))
        .setName(SubscriptionName.of("test-stats-consumer"))
        .build();

    // Kafka properties
    Map<String, Object> kafkaProps = new HashMap<>();
    kafkaProps.put("bootstrap.servers", bootstrapServers);
    kafkaProps.put("security.protocol", "SASL_SSL");
    kafkaProps.put("sasl.mechanism", "OAUTHBEARER");
    kafkaProps.put("sasl.login.callback.handler.class",
        "com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler");
    kafkaProps.put("sasl.jaas.config",
        "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;");

    // Publisher settings
    kafkaProps.put("acks", "all");
    kafkaProps.put("retries", 3);
    kafkaProps.put("request.timeout.ms", 30000);

    // Create clients
    KafkaTopicStatsClient statsClient = new KafkaTopicStatsClient(region, kafkaProps);
    KafkaCursorClient cursorClient = new KafkaCursorClient(region, kafkaProps);

    try {
      // ========== Phase 1: Get Initial Topic Stats ==========
      System.out.println("=== Phase 1: Initial Topic Stats ===");
      System.out.println();

      Partition partition = Partition.of(0);

      // Get earliest offset
      System.out.println("Getting earliest offset for partition 0...");
      Offset earliestOffset = statsClient.getEarliestOffset(topicName, partition).get(30, TimeUnit.SECONDS);
      System.out.println("  Earliest offset: " + earliestOffset.value());

      // Get latest offset
      System.out.println("Getting latest offset for partition 0...");
      Offset latestOffset = statsClient.getLatestOffset(topicName, partition).get(30, TimeUnit.SECONDS);
      System.out.println("  Latest offset: " + latestOffset.value());

      // Compute head cursor (same as latest)
      System.out.println("Computing head cursor for partition 0...");
      Cursor headCursor = statsClient.computeHeadCursor(topicPath, partition).get(30, TimeUnit.SECONDS);
      System.out.println("  Head cursor offset: " + headCursor.getOffset());

      // Compute message stats between earliest and latest
      System.out.println("Computing message stats...");
      ComputeMessageStatsResponse stats = statsClient.computeMessageStats(
          topicPath, partition, earliestOffset, latestOffset).get(30, TimeUnit.SECONDS);
      System.out.println("  Message count: " + stats.getMessageCount());
      System.out.println("  Estimated bytes: " + stats.getMessageBytes());
      System.out.println();

      // ========== Phase 2: Publish Test Messages ==========
      System.out.println("=== Phase 2: Publishing Test Messages ===");
      System.out.println();

      PublisherSettings publisherSettings = PublisherSettings.newBuilder()
          .setTopicPath(topicPath)
          .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
          .setKafkaProperties(kafkaProps)
          .build();

      Publisher publisher = Publisher.create(publisherSettings);
      publisher.startAsync().awaitRunning();

      try {
        for (int i = 1; i <= numMessages; i++) {
          String payload = "Stats test message #" + i;
          PubsubMessage message = PubsubMessage.newBuilder()
              .setData(ByteString.copyFromUtf8(payload))
              .putAttributes("test", "stats")
              .putAttributes("index", String.valueOf(i))
              .build();

          String messageId = publisher.publish(message).get(30, TimeUnit.SECONDS);
          System.out.println("  Published: " + payload + " -> " + messageId);
        }
      } finally {
        publisher.stopAsync().awaitTerminated();
      }

      System.out.println();
      System.out.println("Published " + numMessages + " messages.");
      System.out.println();

      // ========== Phase 3: Get Updated Topic Stats ==========
      System.out.println("=== Phase 3: Updated Topic Stats ===");
      System.out.println();

      // Get new latest offset
      Offset newLatestOffset = statsClient.getLatestOffset(topicName, partition).get(30, TimeUnit.SECONDS);
      System.out.println("  New latest offset: " + newLatestOffset.value());
      System.out.println("  Offset delta: " + (newLatestOffset.value() - latestOffset.value()));

      // Compute stats for new messages
      ComputeMessageStatsResponse newStats = statsClient.computeMessageStats(
          topicPath, partition, latestOffset, newLatestOffset).get(30, TimeUnit.SECONDS);
      System.out.println("  New messages count: " + newStats.getMessageCount());
      System.out.println("  New messages bytes (estimated): " + newStats.getMessageBytes());
      System.out.println();

      // ========== Phase 4: Cursor Management ==========
      System.out.println("=== Phase 4: Cursor Management ===");
      System.out.println();

      // Read current committed offset (may be empty if consumer never committed)
      System.out.println("Reading committed offsets for subscription...");
      List<PartitionCursor> cursors = cursorClient.readCommittedOffsets(subscriptionPath, topicName)
          .get(30, TimeUnit.SECONDS);
      if (cursors.isEmpty()) {
        System.out.println("  No committed offsets found (new consumer group)");
      } else {
        for (PartitionCursor pc : cursors) {
          System.out.println("  Partition " + pc.getPartition() + ": offset " + pc.getCursor().getOffset());
        }
      }

      // Commit a new offset
      Offset commitOffset = Offset.of(newLatestOffset.value());
      System.out.println("Committing offset " + commitOffset.value() + " for partition 0...");
      cursorClient.commitOffset(subscriptionPath, topicName, partition, commitOffset)
          .get(30, TimeUnit.SECONDS);
      System.out.println("  Offset committed successfully.");

      // Read committed offset again
      System.out.println("Reading committed offset after commit...");
      Cursor committedCursor = cursorClient.getCommittedOffset(subscriptionPath, topicName, partition)
          .get(30, TimeUnit.SECONDS);
      System.out.println("  Committed offset: " + committedCursor.getOffset());
      System.out.println();

      // ========== Phase 5: Backlog Calculation ==========
      System.out.println("=== Phase 5: Backlog Calculation ===");
      System.out.println();

      // Simulate a consumer that's behind by resetting to an earlier offset
      Offset behindOffset = Offset.of(Math.max(0, newLatestOffset.value() - numMessages / 2));
      System.out.println("Setting consumer offset to " + behindOffset.value() + " (simulating lag)...");

      Map<Partition, Offset> resetOffsets = new HashMap<>();
      resetOffsets.put(partition, behindOffset);
      cursorClient.resetOffsets(subscriptionPath, topicName, resetOffsets).get(30, TimeUnit.SECONDS);

      // Calculate backlog
      System.out.println("Calculating backlog...");
      BacklogInfo backlog = statsClient.computeBacklogBytes(
          topicName, partition, subscriptionPath, 100) // Assume 100 bytes avg message size
          .get(30, TimeUnit.SECONDS);
      System.out.println("  Backlog messages: " + backlog.getMessageCount());
      System.out.println("  Estimated backlog bytes: " + backlog.getEstimatedBytes());
      System.out.println();

      // ========== Results ==========
      System.out.println("=== TEST PASSED ===");
      System.out.println();
      System.out.println("Summary:");
      System.out.println("  - Topic stats (earliest/latest offsets): OK");
      System.out.println("  - Message stats computation: OK");
      System.out.println("  - Cursor commit/read: OK");
      System.out.println("  - Offset reset: OK");
      System.out.println("  - Backlog calculation: OK");

    } catch (Exception e) {
      System.out.println();
      System.out.println("=== TEST FAILED ===");
      System.out.println("Error: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    } finally {
      System.out.println();
      System.out.println("Closing clients...");
      statsClient.close();
      cursorClient.close();
      System.out.println("Done.");
    }
  }
}
