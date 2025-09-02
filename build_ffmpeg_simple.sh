#!/bin/bash

# Simple FFmpeg Android Build Script
# Builds FFmpeg for arm64-v8a (64-bit ARM) which is most common

set -e

echo "Building FFmpeg for Android arm64-v8a..."

# Configuration
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
MIN_SDK=21

# Paths
BUILD_DIR="/tmp/ffmpeg-android-build"
SOURCE_DIR="/tmp/ffmpeg-source"
OUTPUT_DIR="/Users/mustafa/AndroidStudioProjects/FfmpegLib/FFMpegLib/src/main/assets/ffmpeg"

# Create build directory
mkdir -p "$BUILD_DIR"
mkdir -p "$OUTPUT_DIR/arm64-v8a"

# Check if source exists
if [ ! -d "$SOURCE_DIR" ]; then
    echo "FFmpeg source not found. Cloning..."
    cd /tmp
    git clone --depth 1 --branch n6.0 https://github.com/FFmpeg/FFmpeg.git ffmpeg-source
fi

cd "$SOURCE_DIR"

# Clean previous build
make clean 2>/dev/null || true

# Set up toolchain for arm64-v8a
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"
CC="$TOOLCHAIN/bin/aarch64-linux-android${MIN_SDK}-clang"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${MIN_SDK}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
AS="$CC"
NM="$TOOLCHAIN/bin/llvm-nm"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"

echo "Configuring FFmpeg..."

# Configure FFmpeg for Android
./configure \
    --prefix="$BUILD_DIR/output" \
    --target-os=android \
    --arch=aarch64 \
    --cpu=armv8-a \
    --enable-cross-compile \
    --cc="$CC" \
    --cxx="$CXX" \
    --ar="$AR" \
    --as="$AS" \
    --nm="$NM" \
    --ranlib="$RANLIB" \
    --strip="$STRIP" \
    --sysroot="$SYSROOT" \
    --extra-cflags="-O3 -fPIC -DANDROID -Wno-deprecated" \
    --extra-ldflags="-Wl,-rpath-link=$SYSROOT/usr/lib/aarch64-linux-android/$MIN_SDK -L$SYSROOT/usr/lib/aarch64-linux-android/$MIN_SDK $TOOLCHAIN/lib/clang/18/lib/linux/libclang_rt.builtins-aarch64-android.a -lc -lm -ldl -llog -static-libgcc" \
    --enable-static \
    --disable-shared \
    --disable-doc \
    --disable-ffplay \
    --disable-ffprobe \
    --disable-avdevice \
    --disable-symver \
    --enable-small \
    --enable-gpl \
    --enable-pic \
    --disable-debug

echo "Building FFmpeg..."
make -j$(sysctl -n hw.ncpu)

echo "Installing..."
make install

# Copy the libraries to assets
if [ -f "$BUILD_DIR/output/bin/ffmpeg" ]; then
    cp "$BUILD_DIR/output/bin/ffmpeg" "$OUTPUT_DIR/arm64-v8a/libffmpeg.so"
    chmod 755 "$OUTPUT_DIR/arm64-v8a/libffmpeg.so"
    echo "✓ FFmpeg binary copied to assets"
else
    echo "Building as standalone executable..."
    # Try to build just the ffmpeg executable
    make ffmpeg
    if [ -f "ffmpeg" ]; then
        cp ffmpeg "$OUTPUT_DIR/arm64-v8a/libffmpeg.so"
        chmod 755 "$OUTPUT_DIR/arm64-v8a/libffmpeg.so"
        echo "✓ FFmpeg executable copied to assets"
    fi
fi

# Check the binary
file "$OUTPUT_DIR/arm64-v8a/libffmpeg.so"
ls -lh "$OUTPUT_DIR/arm64-v8a/libffmpeg.so"

echo "Build complete! FFmpeg for arm64-v8a is ready."