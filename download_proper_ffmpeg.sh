#!/bin/bash

# Download proper FFmpeg binaries for Android
# Using FFmpeg Kit releases which are properly compiled for Android

set -e

echo "Downloading proper Android FFmpeg binaries..."

# Create temp directory
TEMP_DIR="/tmp/ffmpeg-android-download"
mkdir -p "$TEMP_DIR"
cd "$TEMP_DIR"

# Download FFmpeg Kit Min (smaller size, basic codecs)
echo "Downloading FFmpeg Kit 5.1..."
curl -L -o ffmpegkit.zip "https://github.com/arthenica/ffmpeg-kit/releases/download/v5.1/ffmpeg-kit-min-5.1-android-aar.zip"

# Extract
echo "Extracting..."
unzip -q ffmpegkit.zip

# The AAR is actually a ZIP file
mv ffmpeg-kit-min-5.1.aar ffmpeg-kit-min-5.1.zip
unzip -q ffmpeg-kit-min-5.1.zip

# Copy binaries to assets
ASSETS_DIR="/Users/mustafa/AndroidStudioProjects/FfmpegLib/FFMpegLib/src/main/assets/ffmpeg"

echo "Copying binaries to assets..."

# Copy arm64-v8a
if [ -d "jni/arm64-v8a" ]; then
    mkdir -p "$ASSETS_DIR/arm64-v8a"
    cp jni/arm64-v8a/libffmpegkit.so "$ASSETS_DIR/arm64-v8a/libffmpeg.so" 2>/dev/null || true
    echo "✓ Copied arm64-v8a"
fi

# Copy armeabi-v7a  
if [ -d "jni/armeabi-v7a" ]; then
    mkdir -p "$ASSETS_DIR/armeabi-v7a"
    cp jni/armeabi-v7a/libffmpegkit.so "$ASSETS_DIR/armeabi-v7a/libffmpeg.so" 2>/dev/null || true
    echo "✓ Copied armeabi-v7a"
fi

# Copy x86
if [ -d "jni/x86" ]; then
    mkdir -p "$ASSETS_DIR/x86"
    cp jni/x86/libffmpegkit.so "$ASSETS_DIR/x86/libffmpeg.so" 2>/dev/null || true
    echo "✓ Copied x86"
fi

# Copy x86_64
if [ -d "jni/x86_64" ]; then
    mkdir -p "$ASSETS_DIR/x86_64"
    cp jni/x86_64/libffmpegkit.so "$ASSETS_DIR/x86_64/libffmpeg.so" 2>/dev/null || true
    echo "✓ Copied x86_64"
fi

# Clean up
cd /
rm -rf "$TEMP_DIR"

echo "Download complete!"
echo ""
echo "Note: These are wrapper libraries. For standalone executables, run build_ffmpeg_android.sh"