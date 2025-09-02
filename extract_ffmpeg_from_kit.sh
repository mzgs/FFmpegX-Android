#!/bin/bash

# Script to extract FFmpeg binaries from ffmpeg-kit AAR
# These binaries are properly compiled for Android and work on all versions

set -e

echo "Extracting FFmpeg binaries from ffmpeg-kit..."

# Create temp directory
TEMP_DIR="/tmp/ffmpeg-kit-extract"
OUTPUT_DIR="/Users/mustafa/AndroidStudioProjects/FfmpegLib/FFMpegLib/src/main/assets/ffmpeg"

rm -rf "$TEMP_DIR"
mkdir -p "$TEMP_DIR"
cd "$TEMP_DIR"

# Download ffmpeg-kit AAR (min version for smaller size)
echo "Downloading ffmpeg-kit-min AAR..."
curl -L -o ffmpeg-kit.aar "https://repo1.maven.org/maven2/com/arthenica/ffmpeg-kit-min/6.0-2/ffmpeg-kit-min-6.0-2.aar"

# Extract AAR (it's a zip file)
echo "Extracting AAR..."
unzip -q ffmpeg-kit.aar

# Extract the JNI libraries
echo "Extracting JNI libraries..."
cd jni

# Create output directories
mkdir -p "$OUTPUT_DIR"

# Copy FFmpeg binaries for each architecture
for arch in arm64-v8a armeabi-v7a x86 x86_64; do
    if [ -d "$arch" ]; then
        echo "Processing $arch..."
        mkdir -p "$OUTPUT_DIR/$arch"
        
        # The actual ffmpeg binary is in libffmpegkit.so
        # But we need the actual ffmpeg executable, not the JNI wrapper
        # Let's check what's available
        ls -la "$arch/" || true
        
        # Copy any ffmpeg-related binaries
        if [ -f "$arch/libffmpegkit.so" ]; then
            cp "$arch/libffmpegkit.so" "$OUTPUT_DIR/$arch/"
            echo "  Copied libffmpegkit.so"
        fi
    fi
done

# Actually, ffmpeg-kit doesn't ship standalone binaries, only JNI libraries
# We need to download pre-built Android binaries from another source

echo ""
echo "Note: ffmpeg-kit only contains JNI libraries, not standalone executables."
echo "We need to get pre-built Android FFmpeg binaries from another source."
echo ""

# Clean up
cd /
rm -rf "$TEMP_DIR"

echo "Let's download pre-built Android FFmpeg binaries instead..."

# Download pre-built FFmpeg binaries for Android
cd /tmp

echo "Downloading pre-built FFmpeg for Android from a reliable source..."

# We'll build our own or use the ones we already built
echo "Actually, we already have a working FFmpeg binary that we built earlier."
echo "The issue is just with Android 10+ execution permissions."
echo ""
echo "Options:"
echo "1. Use the FFmpeg binary we built (13MB) with targetSdk 28"
echo "2. Package it as a proper JNI library"
echo "3. Use a different execution method"