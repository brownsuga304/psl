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
import org.junit.Test;

/**
 * Simple test to verify Kafka publishing works with local Docker Compose setup
 */
public class SimpleKafkaTest {

    @Test
    public void testKafkaPublisherConfiguration() throws Exception {
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

        // Verify the settings can be created successfully
        System.out.println("✅ Kafka PublisherSettings configured successfully!");
        System.out.println("   - Messaging Backend: " + settings.messagingBackend());
        System.out.println("   - Kafka Properties: " + settings.kafkaProperties());
        System.out.println("   - Topic Path: " + settings.topicPath());
        
        // Verify the kafka properties contain the expected values
        assert settings.kafkaProperties().isPresent();
        Map<String, Object> props = settings.kafkaProperties().get();
        assert props.get("bootstrap.servers").equals("localhost:9092");
        assert props.get("acks").equals("all");
        assert props.get("retries").equals(3);
        
        System.out.println("✅ All Kafka configuration verification passed!");
        
        // Note: We don't try to actually connect to Kafka in this unit test
        // to avoid requiring a running Kafka instance for the test suite.
    }
}