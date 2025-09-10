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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

@RunWith(JUnit4.class)
@Ignore("Testcontainers integration tests - use KafkaLiveTest with Docker Compose instead")
public class KafkaIntegrationTest {

  private static final String KAFKA_IMAGE = "confluentinc/cp-kafka:7.4.0";
  private static final String TEST_TOPIC = "test-topic";
  
  @Container
  public KafkaContainer kafka = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));

  private Publisher publisher;
  private KafkaConsumer<byte[], byte[]> kafkaConsumer;

  @Before
  public void setUp() throws Exception {
    kafka.start();
    
    // Create topic path
    TopicPath topicPath = TopicPath.newBuilder()
        .setProject(ProjectNumber.of(123456L))
        .setLocation(CloudZone.of(CloudRegion.of("us-central1"), 'a'))
        .setName(TopicName.of(TEST_TOPIC))
        .build();

    // Configure Kafka properties
    Map<String, Object> kafkaProperties = new HashMap<>();
    kafkaProperties.put("bootstrap.servers", kafka.getBootstrapServers());
    kafkaProperties.put("acks", "all");
    kafkaProperties.put("retries", 3);

    // Create publisher settings for Kafka
    PublisherSettings settings = PublisherSettings.newBuilder()
        .setTopicPath(topicPath)
        .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
        .setKafkaProperties(kafkaProperties)
        .build();

    // Create and start publisher
    publisher = Publisher.create(settings);
    publisher.startAsync().awaitRunning();

    // Create Kafka consumer for verification
    Map<String, Object> consumerProps = new HashMap<>();
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group");
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    
    kafkaConsumer = new KafkaConsumer<>(consumerProps);
    kafkaConsumer.subscribe(java.util.Arrays.asList(TEST_TOPIC));
  }

  @After
  public void tearDown() throws Exception {
    if (publisher != null) {
      publisher.stopAsync().awaitTerminated();
    }
    if (kafkaConsumer != null) {
      kafkaConsumer.close();
    }
    kafka.stop();
  }

  @Test
  public void testPublishSingleMessage() throws Exception {
    // Create test message
    PubsubMessage message = PubsubMessage.newBuilder()
        .setData(ByteString.copyFromUtf8("Hello Kafka!"))
        .putAttributes("source", "integration-test")
        .putAttributes("timestamp", String.valueOf(System.currentTimeMillis()))
        .build();

    // Publish message
    ApiFuture<String> future = publisher.publish(message);
    String messageId = future.get(30, TimeUnit.SECONDS);

    // Verify message ID is returned
    assertThat(messageId).isNotEmpty();
    assertThat(messageId).contains("0:0"); // partition:offset format

    // Verify message was received in Kafka
    ConsumerRecords<byte[], byte[]> records = kafkaConsumer.poll(Duration.ofSeconds(10));
    assertThat(records.count()).isEqualTo(1);

    ConsumerRecord<byte[], byte[]> record = records.iterator().next();
    assertThat(new String(record.value())).isEqualTo("Hello Kafka!");
    assertThat(record.headers().lastHeader("source")).isNotNull();
    assertThat(new String(record.headers().lastHeader("source").value())).isEqualTo("integration-test");
  }

  @Test
  public void testPublishMultipleMessages() throws Exception {
    int messageCount = 5;
    List<ApiFuture<String>> futures = new ArrayList<>();

    // Publish multiple messages
    for (int i = 0; i < messageCount; i++) {
      PubsubMessage message = PubsubMessage.newBuilder()
          .setData(ByteString.copyFromUtf8("Message " + i))
          .putAttributes("messageNumber", String.valueOf(i))
          .build();

      ApiFuture<String> future = publisher.publish(message);
      futures.add(future);
    }

    // Wait for all messages to be published
    List<String> messageIds = new ArrayList<>();
    for (ApiFuture<String> future : futures) {
      String messageId = future.get(30, TimeUnit.SECONDS);
      messageIds.add(messageId);
    }

    // Verify all message IDs are unique
    assertThat(messageIds).hasSize(messageCount);
    assertThat(messageIds.stream().distinct().count()).isEqualTo(messageCount);

    // No flush method available in the interface

    // Verify all messages were received in Kafka
    List<ConsumerRecord<byte[], byte[]>> allRecords = new ArrayList<>();
    long endTime = System.currentTimeMillis() + 10000; // 10 second timeout

    while (System.currentTimeMillis() < endTime && allRecords.size() < messageCount) {
      ConsumerRecords<byte[], byte[]> records = kafkaConsumer.poll(Duration.ofMillis(1000));
      records.forEach(allRecords::add);
    }

    assertThat(allRecords).hasSize(messageCount);

    // Verify message contents
    for (int i = 0; i < messageCount; i++) {
      boolean found = false;
      for (ConsumerRecord<byte[], byte[]> record : allRecords) {
        if (new String(record.value()).equals("Message " + i)) {
          found = true;
          assertThat(record.headers().lastHeader("messageNumber")).isNotNull();
          assertThat(new String(record.headers().lastHeader("messageNumber").value()))
              .isEqualTo(String.valueOf(i));
          break;
        }
      }
      assertThat(found).isTrue();
    }
  }

  @Test
  public void testPublishWithOrderingKey() throws Exception {
    // Create message with ordering key
    PubsubMessage message = PubsubMessage.newBuilder()
        .setData(ByteString.copyFromUtf8("Ordered message"))
        .setOrderingKey("test-key")
        .putAttributes("ordered", "true")
        .build();

    // Publish message
    ApiFuture<String> future = publisher.publish(message);
    String messageId = future.get(30, TimeUnit.SECONDS);

    assertThat(messageId).isNotEmpty();

    // Verify message in Kafka has the key
    ConsumerRecords<byte[], byte[]> records = kafkaConsumer.poll(Duration.ofSeconds(10));
    assertThat(records.count()).isEqualTo(1);

    ConsumerRecord<byte[], byte[]> record = records.iterator().next();
    assertThat(record.key()).isEqualTo("test-key".getBytes());
    assertThat(new String(record.value())).isEqualTo("Ordered message");
  }

  @Test(expected = ExecutionException.class)
  public void testPublishToInvalidTopic() throws Exception {
    // Try to publish when Kafka is stopped (should fail)
    kafka.stop();
    
    PubsubMessage message = PubsubMessage.newBuilder()
        .setData(ByteString.copyFromUtf8("This should fail"))
        .build();

    ApiFuture<String> future = publisher.publish(message);
    // This should throw ExecutionException due to connection failure
    future.get(5, TimeUnit.SECONDS);
  }
}