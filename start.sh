#!/bin/bash
# Shell script to run Kraken Rebalancer on macOS / Linux

# Ensure we run from the script directory
cd "$(dirname "$0")"

# Check if JAR exists, if not build it
JAR_PATH="build/libs/kraken-bot-0.0.1-SNAPSHOT-all.jar"
if [ ! -f "$JAR_PATH" ]; then
    echo "Executable JAR not found. Building with Gradle..."
    ./gradlew fatJar
    if [ $? -ne 0 ]; then
        echo "Gradle build failed. Exiting."
        exit 1
    fi
fi

echo "Starting Kraken Rebalancer..."
java -Xshare:off --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -jar "$JAR_PATH"
