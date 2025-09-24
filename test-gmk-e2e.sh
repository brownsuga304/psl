#!/bin/bash

# End-to-end test script for GMK integration
# This script tests both publisher and subscriber against a real GMK cluster

set -e

# Configuration - UPDATE THESE VALUES
PROJECT_ID="${GMK_PROJECT_ID:-arkam-test}"
REGION="${GMK_REGION:-us-central1}"
CLUSTER_ID="${GMK_CLUSTER_ID:-test}"
TOPIC_NAME="${GMK_TOPIC:-my-gmk-topic}"
ZONE="${GMK_ZONE:-us-central1-a}"  # For creating test VM if needed

echo "=========================================="
echo "🚀 GMK End-to-End Test"
echo "=========================================="
echo "Project:  $PROJECT_ID"
echo "Region:   $REGION"
echo "Cluster:  $CLUSTER_ID"
echo "Topic:    $TOPIC_NAME"
echo ""

# Function to check if GMK cluster exists
check_gmk_cluster() {
    echo "🔍 Checking GMK cluster..."
    if gcloud managed-kafka clusters describe $CLUSTER_ID \
        --location=$REGION \
        --project=$PROJECT_ID &>/dev/null; then
        echo "✅ GMK cluster '$CLUSTER_ID' exists"
        return 0
    else
        echo "❌ GMK cluster '$CLUSTER_ID' not found"
        return 1
    fi
}

# Function to check if topic exists
check_topic() {
    echo "🔍 Checking topic '$TOPIC_NAME'..."
    if gcloud managed-kafka topics describe $TOPIC_NAME \
        --cluster=$CLUSTER_ID \
        --location=$REGION \
        --project=$PROJECT_ID &>/dev/null; then
        echo "✅ Topic '$TOPIC_NAME' exists"
        return 0
    else
        echo "⚠️  Topic '$TOPIC_NAME' not found"
        return 1
    fi
}

# Function to create topic
create_topic() {
    echo "📝 Creating topic '$TOPIC_NAME'..."
    gcloud managed-kafka topics create $TOPIC_NAME \
        --cluster=$CLUSTER_ID \
        --location=$REGION \
        --project=$PROJECT_ID \
        --partitions=3 \
        --replication-factor=3 \
        --retention-ms=86400000
    echo "✅ Topic created"
}

# Function to setup SSH tunnel for VPC access
setup_tunnel() {
    echo ""
    echo "🔒 Setting up VPC access..."
    echo "Choose access method:"
    echo "1) Cloud Shell tunnel (recommended)"
    echo "2) Compute Engine VM tunnel"
    echo "3) Direct connection (if no VPC restrictions)"
    read -p "Enter choice (1-3): " choice

    case $choice in
        1)
            echo "📡 Setting up Cloud Shell tunnel..."
            echo "Run this command in a separate terminal:"
            echo ""
            echo "gcloud cloud-shell ssh -- -L 9092:bootstrap.$CLUSTER_ID.$REGION.managedkafka.$PROJECT_ID.cloud.goog:9092"
            echo ""
            echo "Then update the bootstrap servers to: localhost:9092"
            read -p "Press Enter when tunnel is ready..."
            export BOOTSTRAP_SERVERS="localhost:9092"
            ;;
        2)
            echo "🖥️  Setting up Compute Engine VM tunnel..."
            VM_NAME="gmk-test-vm-$(date +%s)"
            echo "Creating VM: $VM_NAME"

            # Get the VPC network from the cluster
            NETWORK=$(gcloud managed-kafka clusters describe $CLUSTER_ID \
                --location=$REGION \
                --project=$PROJECT_ID \
                --format="value(network)")

            # Create VM in the same VPC
            gcloud compute instances create $VM_NAME \
                --zone=$ZONE \
                --network=$NETWORK \
                --subnet=$NETWORK \
                --project=$PROJECT_ID

            echo "Starting SSH tunnel through VM..."
            gcloud compute ssh $VM_NAME --zone=$ZONE --project=$PROJECT_ID -- \
                -L 9092:bootstrap.$CLUSTER_ID.$REGION.managedkafka.$PROJECT_ID.cloud.goog:9092 &

            sleep 5
            export BOOTSTRAP_SERVERS="localhost:9092"
            ;;
        3)
            echo "🌐 Using direct connection..."
            export BOOTSTRAP_SERVERS="bootstrap.$CLUSTER_ID.$REGION.managedkafka.$PROJECT_ID.cloud.goog:9092"
            ;;
    esac
}

# Function to build the project
build_project() {
    echo ""
    echo "🔨 Building java-pubsublite with Kafka support..."
    mvn clean compile -DskipTests
    if [ $? -ne 0 ]; then
        echo "❌ Build failed"
        exit 1
    fi
    echo "✅ Build successful"
}

# Function to run publisher test
test_publisher() {
    echo ""
    echo "📤 Testing Publisher..."

    # Create a test publisher
    cat > TestGmkPublisher.java << 'EOF'
import com.google.api.core.ApiFuture;
import com.google.cloud.pubsublite.CloudRegion;
import com.google.cloud.pubsublite.ProjectId;
import com.google.cloud.pubsublite.TopicName;
import com.google.cloud.pubsublite.TopicPath;
import com.google.cloud.pubsublite.cloudpubsub.MessagingBackend;
import com.google.cloud.pubsublite.cloudpubsub.Publisher;
import com.google.cloud.pubsublite.cloudpubsub.PublisherSettings;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TestGmkPublisher {
    public static void main(String[] args) throws Exception {
        String projectId = System.getenv("PROJECT_ID");
        String region = System.getenv("REGION");
        String topicName = System.getenv("TOPIC_NAME");
        String bootstrapServers = System.getenv("BOOTSTRAP_SERVERS");

        TopicPath topicPath = TopicPath.newBuilder()
            .setProject(ProjectId.of(projectId))
            .setLocation(CloudRegion.of(region))
            .setName(TopicName.of(topicName))
            .build();

        Map<String, Object> kafkaProps = new HashMap<>();
        kafkaProps.put("bootstrap.servers", bootstrapServers);

        // Add GMK authentication if not using localhost
        if (!bootstrapServers.startsWith("localhost")) {
            kafkaProps.put("security.protocol", "SASL_SSL");
            kafkaProps.put("sasl.mechanism", "OAUTHBEARER");
            kafkaProps.put("sasl.login.callback.handler.class",
                "com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler");
            kafkaProps.put("sasl.jaas.config",
                "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;");
        }

        PublisherSettings settings = PublisherSettings.newBuilder()
            .setTopicPath(topicPath)
            .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
            .setKafkaProperties(kafkaProps)
            .build();

        Publisher publisher = Publisher.create(settings);
        publisher.startAsync().awaitRunning();

        System.out.println("Publishing 10 test messages...");
        List<ApiFuture<String>> futures = new ArrayList<>();

        for (int i = 1; i <= 10; i++) {
            PubsubMessage message = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("Test message #" + i + " at " + System.currentTimeMillis()))
                .putAttributes("test", "true")
                .putAttributes("index", String.valueOf(i))
                .build();

            futures.add(publisher.publish(message));
            System.out.println("Published message #" + i);
            Thread.sleep(100);
        }

        // Wait for all publishes to complete
        for (int i = 0; i < futures.size(); i++) {
            String messageId = futures.get(i).get();
            System.out.println("Message #" + (i+1) + " confirmed with ID: " + messageId);
        }

        publisher.stopAsync().awaitTerminated(5, TimeUnit.SECONDS);
        System.out.println("✅ Publisher test completed!");
    }
}
EOF

    # Compile and run
    javac -cp "google-cloud-pubsublite/target/classes:google-cloud-pubsublite/target/lib/*" TestGmkPublisher.java

    PROJECT_ID=$PROJECT_ID \
    REGION=$REGION \
    TOPIC_NAME=$TOPIC_NAME \
    BOOTSTRAP_SERVERS=$BOOTSTRAP_SERVERS \
    java -cp ".:google-cloud-pubsublite/target/classes:google-cloud-pubsublite/target/lib/*" TestGmkPublisher
}

# Function to run subscriber test
test_subscriber() {
    echo ""
    echo "📥 Testing Subscriber..."

    # Create a test subscriber
    cat > TestGmkSubscriber.java << 'EOF'
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TestGmkSubscriber {
    public static void main(String[] args) throws Exception {
        String projectId = System.getenv("PROJECT_ID");
        String region = System.getenv("REGION");
        String topicName = System.getenv("TOPIC_NAME");
        String bootstrapServers = System.getenv("BOOTSTRAP_SERVERS");

        SubscriptionPath subscriptionPath = SubscriptionPath.newBuilder()
            .setProject(ProjectId.of(projectId))
            .setLocation(CloudRegion.of(region))
            .setName(SubscriptionName.of(topicName + "-sub"))
            .build();

        Map<String, Object> kafkaProps = new HashMap<>();
        kafkaProps.put("bootstrap.servers", bootstrapServers);
        kafkaProps.put("pubsublite.topic.name", topicName);

        // Add GMK authentication if not using localhost
        if (!bootstrapServers.startsWith("localhost")) {
            kafkaProps.put("security.protocol", "SASL_SSL");
            kafkaProps.put("sasl.mechanism", "OAUTHBEARER");
            kafkaProps.put("sasl.login.callback.handler.class",
                "com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler");
            kafkaProps.put("sasl.jaas.config",
                "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;");
        }

        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(10);

        MessageReceiver receiver = (PubsubMessage message, AckReplyConsumer consumer) -> {
            int count = messageCount.incrementAndGet();
            System.out.println("Received message #" + count + ": " + message.getData().toStringUtf8());
            consumer.ack();
            latch.countDown();
        };

        FlowControlSettings flowControl = FlowControlSettings.builder()
            .setBytesOutstanding(10 * 1024 * 1024L)
            .setMessagesOutstanding(100L)
            .build();

        SubscriberSettings settings = SubscriberSettings.newBuilder()
            .setSubscriptionPath(subscriptionPath)
            .setReceiver(receiver)
            .setPerPartitionFlowControlSettings(flowControl)
            .setMessagingBackend(MessagingBackend.MANAGED_KAFKA)
            .setKafkaProperties(kafkaProps)
            .build();

        Subscriber subscriber = Subscriber.create(settings);
        subscriber.startAsync().awaitRunning();

        System.out.println("Waiting for messages (max 30 seconds)...");
        boolean received = latch.await(30, TimeUnit.SECONDS);

        subscriber.stopAsync().awaitTerminated(5, TimeUnit.SECONDS);

        if (received) {
            System.out.println("✅ Subscriber test completed! Received all messages.");
        } else {
            System.out.println("⚠️  Timeout. Received " + messageCount.get() + " messages.");
        }
    }
}
EOF

    # Compile and run
    javac -cp "google-cloud-pubsublite/target/classes:google-cloud-pubsublite/target/lib/*" TestGmkSubscriber.java

    PROJECT_ID=$PROJECT_ID \
    REGION=$REGION \
    TOPIC_NAME=$TOPIC_NAME \
    BOOTSTRAP_SERVERS=$BOOTSTRAP_SERVERS \
    java -cp ".:google-cloud-pubsublite/target/classes:google-cloud-pubsublite/target/lib/*" TestGmkSubscriber
}

# Main execution
main() {
    echo "Starting GMK integration test..."

    # Check prerequisites
    if ! command -v gcloud &> /dev/null; then
        echo "❌ gcloud CLI not found. Please install it first."
        exit 1
    fi

    # Check GMK cluster
    if ! check_gmk_cluster; then
        echo "Please create a GMK cluster first:"
        echo "gcloud managed-kafka clusters create $CLUSTER_ID \\"
        echo "    --location=$REGION \\"
        echo "    --cpu=3 \\"
        echo "    --memory=10GiB \\"
        echo "    --subnets=projects/$PROJECT_ID/regions/$REGION/subnetworks/default"
        exit 1
    fi

    # Check/create topic
    if ! check_topic; then
        create_topic
    fi

    # Setup VPC access
    setup_tunnel

    # Build project
    build_project

    # Run tests
    echo ""
    echo "🧪 Running integration tests..."
    echo "1) Test Publisher only"
    echo "2) Test Subscriber only"
    echo "3) Test both (Publisher then Subscriber)"
    echo "4) Test concurrent (Publisher and Subscriber simultaneously)"
    read -p "Enter choice (1-4): " test_choice

    case $test_choice in
        1)
            test_publisher
            ;;
        2)
            test_subscriber
            ;;
        3)
            test_publisher
            test_subscriber
            ;;
        4)
            echo "Starting concurrent test..."
            test_subscriber &
            SUBSCRIBER_PID=$!
            sleep 2
            test_publisher
            wait $SUBSCRIBER_PID
            ;;
    esac

    echo ""
    echo "=========================================="
    echo "🎉 GMK Integration Test Complete!"
    echo "=========================================="

    # Cleanup
    rm -f TestGmkPublisher.java TestGmkPublisher.class
    rm -f TestGmkSubscriber.java TestGmkSubscriber.class
}

# Run main
main