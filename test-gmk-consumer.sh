#!/bin/bash

# Test script for GMK Consumer using local build

echo "🔨 Building project..."
mvn clean compile -DskipTests

if [ $? -ne 0 ]; then
    echo "❌ Build failed"
    exit 1
fi

echo ""
echo "📦 Compiling GMK Subscriber example..."
javac -cp "google-cloud-pubsublite/target/classes:google-cloud-pubsublite/target/lib/*" \
  examples/GmkSubscriberExample.java

if [ $? -ne 0 ]; then
    echo "❌ Compilation failed"
    exit 1
fi

echo ""
echo "🚀 Running GMK Subscriber with local library build..."
java -cp "examples:google-cloud-pubsublite/target/classes:google-cloud-pubsublite/target/lib/*" \
  GmkSubscriberExample

echo ""
echo "✅ Test complete! Using local build: google-cloud-pubsublite-1.15.15-SNAPSHOT"