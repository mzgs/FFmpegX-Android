#!/bin/bash

# FFmpeg Android Build Script
# This script builds FFmpeg for all Android architectures

set -e

# Configuration
FFMPEG_VERSION="6.0"
NDK_VERSION="25.2.9519653"
MIN_SDK_VERSION="21"
TARGET_SDK_VERSION="33"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}FFmpeg Android Build Script${NC}"
echo "================================"

# Check if Android SDK is set
if [ -z "$ANDROID_HOME" ]; then
    echo -e "${RED}Error: ANDROID_HOME is not set${NC}"
    echo "Please set ANDROID_HOME to your Android SDK path"
    echo "Example: export ANDROID_HOME=/Users/$USER/Library/Android/sdk"
    exit 1
fi

# Set NDK path
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"

# Check if NDK exists
if [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo -e "${YELLOW}NDK not found at $ANDROID_NDK_HOME${NC}"
    echo "Installing NDK..."
    $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;$NDK_VERSION"
fi

# Set paths
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BUILD_DIR="$SCRIPT_DIR/ffmpeg-build"
SOURCE_DIR="$BUILD_DIR/ffmpeg-$FFMPEG_VERSION"
OUTPUT_DIR="$SCRIPT_DIR/FFMpegLib/src/main/assets/ffmpeg"

# Create directories
mkdir -p "$BUILD_DIR"
mkdir -p "$OUTPUT_DIR"

# Download FFmpeg source if not exists
if [ ! -d "$SOURCE_DIR" ]; then
    echo -e "${GREEN}Downloading FFmpeg $FFMPEG_VERSION source...${NC}"
    cd "$BUILD_DIR"
    wget -q --show-progress "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
    tar -xf "ffmpeg-$FFMPEG_VERSION.tar.xz"
    rm "ffmpeg-$FFMPEG_VERSION.tar.xz"
fi

# Function to build FFmpeg for a specific architecture
build_ffmpeg() {
    local ARCH=$1
    local ABI=$2
    local TOOLCHAIN=$3
    local CROSS_PREFIX=$4
    local EXTRA_CFLAGS=$5
    local EXTRA_LDFLAGS=$6
    
    echo -e "${GREEN}Building FFmpeg for $ABI...${NC}"
    
    cd "$SOURCE_DIR"
    
    # Clean previous build
    make clean 2>/dev/null || true
    
    # Set toolchain paths
    TOOLCHAIN_PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64"
    SYSROOT="$TOOLCHAIN_PATH/sysroot"
    CC="$TOOLCHAIN_PATH/bin/${TOOLCHAIN}${MIN_SDK_VERSION}-clang"
    CXX="$TOOLCHAIN_PATH/bin/${TOOLCHAIN}${MIN_SDK_VERSION}-clang++"
    
    # Configure FFmpeg
    ./configure \
        --prefix="$BUILD_DIR/output/$ABI" \
        --target-os=android \
        --arch=$ARCH \
        --enable-cross-compile \
        --enable-runtime-cpudetect \
        --disable-static \
        --enable-shared \
        --disable-doc \
        --disable-ffplay \
        --disable-ffprobe \
        --disable-symver \
        --disable-stripping \
        --cross-prefix="$TOOLCHAIN_PATH/bin/llvm-" \
        --cc="$CC" \
        --cxx="$CXX" \
        --sysroot="$SYSROOT" \
        --extra-cflags="-Os -fpic $EXTRA_CFLAGS" \
        --extra-ldflags="$EXTRA_LDFLAGS" \
        --enable-small \
        --enable-gpl \
        --disable-programs \
        --enable-ffmpeg
    
    # Build
    make -j$(nproc)
    make install
    
    # Copy the binary to assets
    mkdir -p "$OUTPUT_DIR/$ABI"
    if [ -f "$BUILD_DIR/output/$ABI/bin/ffmpeg" ]; then
        cp "$BUILD_DIR/output/$ABI/bin/ffmpeg" "$OUTPUT_DIR/$ABI/libffmpeg.so"
        echo -e "${GREEN}✓ Built $ABI successfully${NC}"
    else
        # If ffmpeg binary not in bin, build as executable
        echo -e "${YELLOW}Building standalone executable for $ABI...${NC}"
        
        # Reconfigure to build programs
        ./configure \
            --prefix="$BUILD_DIR/output/$ABI" \
            --target-os=android \
            --arch=$ARCH \
            --enable-cross-compile \
            --enable-runtime-cpudetect \
            --disable-static \
            --disable-shared \
            --disable-doc \
            --disable-ffplay \
            --disable-symver \
            --cross-prefix="$TOOLCHAIN_PATH/bin/llvm-" \
            --cc="$CC" \
            --cxx="$CXX" \
            --sysroot="$SYSROOT" \
            --extra-cflags="-Os -fpic -fPIE $EXTRA_CFLAGS" \
            --extra-ldflags="-pie $EXTRA_LDFLAGS" \
            --enable-small \
            --enable-gpl
        
        make -j$(nproc) ffmpeg
        cp ffmpeg "$OUTPUT_DIR/$ABI/libffmpeg.so"
        echo -e "${GREEN}✓ Built $ABI executable successfully${NC}"
    fi
}

# Build for all architectures
echo -e "${GREEN}Starting FFmpeg build for all architectures...${NC}"

# arm64-v8a (64-bit ARM)
build_ffmpeg \
    "aarch64" \
    "arm64-v8a" \
    "aarch64-linux-android" \
    "aarch64-linux-android-" \
    "" \
    ""

# armeabi-v7a (32-bit ARM)
build_ffmpeg \
    "arm" \
    "armeabi-v7a" \
    "armv7a-linux-androideabi" \
    "arm-linux-androideabi-" \
    "-march=armv7-a -mfpu=neon -mfloat-abi=softfp" \
    ""

# x86 (32-bit Intel)
build_ffmpeg \
    "x86" \
    "x86" \
    "i686-linux-android" \
    "i686-linux-android-" \
    "-march=i686 -mtune=atom -msse3 -mfpmath=sse" \
    ""

# x86_64 (64-bit Intel)
build_ffmpeg \
    "x86_64" \
    "x86_64" \
    "x86_64-linux-android" \
    "x86_64-linux-android-" \
    "-march=x86-64 -mtune=atom -msse4.2 -mpopcnt" \
    ""

echo -e "${GREEN}================================${NC}"
echo -e "${GREEN}FFmpeg build complete!${NC}"
echo -e "${GREEN}Binaries are in: $OUTPUT_DIR${NC}"

# Make all binaries executable
find "$OUTPUT_DIR" -name "*.so" -exec chmod 755 {} \;

# Show file sizes
echo -e "${YELLOW}Binary sizes:${NC}"
ls -lh "$OUTPUT_DIR"/*/*.so

echo -e "${GREEN}Build successful! You can now use these binaries in your Android app.${NC}"