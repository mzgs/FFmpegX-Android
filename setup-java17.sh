#!/bin/bash

# This script sets up Java 17 for local development
# For JitPack builds, the jitpack.yml handles Java version

echo "Checking for Java 17..."

# Check if Java 17 is available
if /usr/libexec/java_home -v 17 &>/dev/null; then
    export JAVA_HOME=$(/usr/libexec/java_home -v 17)
    echo "Found Java 17 at: $JAVA_HOME"
else
    echo "Java 17 not found. Please install it using one of these methods:"
    echo ""
    echo "Option 1: Using Homebrew (recommended for macOS):"
    echo "  brew install openjdk@17"
    echo "  sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk"
    echo ""
    echo "Option 2: Download from Adoptium:"
    echo "  https://adoptium.net/temurin/releases/?version=17"
    echo ""
    echo "For JitPack builds, this is handled automatically by jitpack.yml"
    exit 1
fi

# Run the build
echo "Running Gradle build with Java 17..."
./gradlew clean :ffmpegx:assembleRelease
./gradlew :ffmpegx:publishToMavenLocal