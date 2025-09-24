#!/bin/bash

# Compile the test client
echo "Compiling ImprovedGmkClient..."
javac -cp "google-cloud-pubsublite/target/google-cloud-pubsublite-1.15.15-SNAPSHOT.jar:google-cloud-pubsublite/target/lib/*" \
  examples/ImprovedGmkClient.java

# Run the test client using your local build
echo "Running GMK test with local library build..."
java -cp "examples:google-cloud-pubsublite/target/google-cloud-pubsublite-1.15.15-SNAPSHOT.jar:google-cloud-pubsublite/target/lib/*" \
  ImprovedGmkClient

# To verify you're using local changes, you can add debug output
echo "Using local build: google-cloud-pubsublite-1.15.15-SNAPSHOT.jar"