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

package com.google.cloud.pubsublite.cloudpubsub.internal;

import com.google.api.core.AbstractApiService;
import com.google.api.core.ApiService;
import com.google.api.gax.rpc.ApiException;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsublite.cloudpubsub.Subscriber;
import com.google.cloud.pubsublite.cloudpubsub.SubscriberSettings;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.PubsubMessage;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

/**
 * A Kafka-based subscriber that uses KafkaConsumer to consume messages from Kafka topics.
 * This implementation is designed to work with Google Managed Kafka (GMK) clusters.
 */
public class KafkaSubscriber extends AbstractApiService implements Subscriber {
  private static final Logger log = Logger.getLogger(KafkaSubscriber.class.getName());

  private final String topicName;
  private final String groupId;
  private final MessageReceiver receiver;
  private final KafkaConsumer<byte[], byte[]> kafkaConsumer;
  private final ExecutorService pollExecutor;
  private final AtomicBoolean isPolling = new AtomicBoolean(false);
  private final Map<String, OffsetInfo> pendingAcks = new ConcurrentHashMap<>();

  // Track offset info for each message
  private static class OffsetInfo {
    final TopicPartition partition;
    final long offset;
    final long timestamp;

    OffsetInfo(TopicPartition partition, long offset, long timestamp) {
      this.partition = partition;
      this.offset = offset;
      this.timestamp = timestamp;
    }
  }

  public KafkaSubscriber(SubscriberSettings settings) {
    this.topicName = settings.subscriptionPath().name().value();
    this.groupId = settings.subscriptionPath().toString().replace('/', '-');
    this.receiver = settings.receiver();
    this.pollExecutor = Executors.newSingleThreadExecutor(
        r -> {
          Thread t = new Thread(r, "kafka-subscriber-poll-" + topicName);
          t.setDaemon(true);
          return t;
        });

    // Set up Kafka consumer configuration
    Map<String, Object> kafkaProps = new HashMap<>(settings.kafkaProperties().orElse(new HashMap<>()));

    // Set required properties
    kafkaProps.putIfAbsent("key.deserializer", ByteArrayDeserializer.class.getName());
    kafkaProps.putIfAbsent("value.deserializer", ByteArrayDeserializer.class.getName());
    kafkaProps.putIfAbsent("group.id", groupId);
    kafkaProps.putIfAbsent("enable.auto.commit", "false"); // Manual offset management
    kafkaProps.putIfAbsent("auto.offset.reset", "earliest");
    kafkaProps.putIfAbsent("max.poll.records", "500");
    kafkaProps.putIfAbsent("session.timeout.ms", "30000");

    try {
      this.kafkaConsumer = new KafkaConsumer<>(kafkaProps);
    } catch (Exception e) {
      String bootstrapServers = (String) kafkaProps.get("bootstrap.servers");
      if (e.getCause() instanceof org.apache.kafka.common.config.ConfigException
          && e.getMessage().contains("No resolvable bootstrap urls")) {
        throw new RuntimeException(
            "Failed to resolve Kafka bootstrap servers: " + bootstrapServers + ". " +
            "This could indicate:\n" +
            "1. The Google Managed Kafka cluster doesn't exist or isn't accessible\n" +
            "2. Network/DNS resolution issues\n" +
            "3. Incorrect bootstrap server URL format\n" +
            "Please verify the cluster exists with: gcloud managed-kafka clusters describe <cluster-name> --location=<region> --project=<project>",
            e);
      }
      throw new RuntimeException("Failed to initialize Kafka consumer: " + e.getMessage(), e);
    }
  }

  private void startPolling() {
    if (!isPolling.compareAndSet(false, true)) {
      return;
    }

    // Subscribe to the topic
    kafkaConsumer.subscribe(Collections.singletonList(topicName));

    // Start the polling loop
    pollExecutor.submit(() -> {
      try {
        while (isPolling.get() && !Thread.currentThread().isInterrupted()) {
          try {
            ConsumerRecords<byte[], byte[]> records = kafkaConsumer.poll(Duration.ofMillis(100));

            for (ConsumerRecord<byte[], byte[]> record : records) {
              if (!isPolling.get()) break;

              try {
                // Convert Kafka record to PubsubMessage
                PubsubMessage message = convertToPubsubMessage(record);

                // Generate a unique message ID
                String messageId = String.format("%s:%d:%d",
                    record.topic(), record.partition(), record.offset());

                // Store offset info for later acknowledgment
                pendingAcks.put(messageId, new OffsetInfo(
                    new TopicPartition(record.topic(), record.partition()),
                    record.offset(),
                    record.timestamp()));

                // Create AckReplyConsumer for this message
                AckReplyConsumer ackReplyConsumer = new AckReplyConsumer() {
                  private final AtomicBoolean acked = new AtomicBoolean(false);

                  @Override
                  public void ack() {
                    if (acked.compareAndSet(false, true)) {
                      commitOffset(messageId);
                    }
                  }

                  @Override
                  public void nack() {
                    if (acked.compareAndSet(false, true)) {
                      // In Kafka, nack typically means we don't commit the offset
                      // The message will be redelivered after session timeout
                      log.info("Message nacked, will be redelivered: " + messageId);
                      pendingAcks.remove(messageId);
                    }
                  }
                };

                // Deliver message to receiver
                receiver.receiveMessage(message, ackReplyConsumer);

              } catch (Exception e) {
                log.log(Level.WARNING, "Error processing message from Kafka", e);
              }
            }

          } catch (WakeupException e) {
            // This is expected when consumer.wakeup() is called
            break;
          } catch (Exception e) {
            log.log(Level.SEVERE, "Error in Kafka poll loop", e);
            if (!isPolling.get()) break;

            // Sleep briefly before retrying
            try {
              Thread.sleep(1000);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              break;
            }
          }
        }
      } finally {
        isPolling.set(false);
      }
    });
  }

  private PubsubMessage convertToPubsubMessage(ConsumerRecord<byte[], byte[]> record) {
    PubsubMessage.Builder builder = PubsubMessage.newBuilder();

    // Set message data
    if (record.value() != null) {
      builder.setData(ByteString.copyFrom(record.value()));
    }

    // Set ordering key from Kafka key if present
    if (record.key() != null) {
      builder.setOrderingKey(new String(record.key()));
    }

    // Convert headers to attributes
    Map<String, String> attributes = new HashMap<>();
    for (Header header : record.headers()) {
      if (header.value() != null) {
        // Skip special headers
        if (header.key().equals("pubsublite.publish_time")) {
          try {
            long seconds = Long.parseLong(new String(header.value()));
            builder.setPublishTime(Timestamp.newBuilder().setSeconds(seconds).build());
          } catch (NumberFormatException e) {
            // Ignore invalid timestamp
          }
        } else {
          attributes.put(header.key(), new String(header.value()));
        }
      }
    }

    // Add Kafka-specific metadata as attributes
    attributes.put("kafka.topic", record.topic());
    attributes.put("kafka.partition", String.valueOf(record.partition()));
    attributes.put("kafka.offset", String.valueOf(record.offset()));
    attributes.put("kafka.timestamp", String.valueOf(record.timestamp()));

    builder.putAllAttributes(attributes);

    // Set message ID (this will be overridden by the framework)
    builder.setMessageId(String.format("%s:%d:%d",
        record.topic(), record.partition(), record.offset()));

    return builder.build();
  }

  private void commitOffset(String messageId) {
    OffsetInfo info = pendingAcks.remove(messageId);
    if (info != null) {
      try {
        // Commit the offset for this message
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        offsets.put(info.partition, new OffsetAndMetadata(info.offset + 1));
        kafkaConsumer.commitSync(offsets);

        log.fine("Committed offset for message: " + messageId);
      } catch (Exception e) {
        log.log(Level.WARNING, "Failed to commit offset for message: " + messageId, e);
      }
    }
  }

  public String getSubscriptionNameString() {
    return topicName + "/" + groupId;
  }

  @Override
  public ApiService startAsync() {
    // Start parent service first
    super.startAsync();
    return this;
  }

  @Override
  protected void doStart() {
    try {
      startPolling();
      notifyStarted();
    } catch (Exception e) {
      notifyFailed(e);
    }
  }

  @Override
  protected void doStop() {
    try {
      // Stop polling
      isPolling.set(false);

      // Wake up the consumer if it's blocked in poll()
      kafkaConsumer.wakeup();

      // Shutdown executor
      pollExecutor.shutdown();
      try {
        if (!pollExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
          pollExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        pollExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }

      // Close consumer
      kafkaConsumer.close();

      notifyStopped();
    } catch (Exception e) {
      notifyFailed(e);
    }
  }
}