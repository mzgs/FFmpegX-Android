#!/bin/bash

# Build FFmpeg as a shared library for Android
# This creates a .so file that can be loaded with dlopen() and called via JNI

set -e

# Android NDK path
NDK_PATH="/Users/mustafa/Library/Android/sdk/ndk/27.0.12077973"
if [ ! -d "$NDK_PATH" ]; then
    echo "Error: NDK not found at $NDK_PATH"
    echo "Please update the NDK_PATH variable in this script"
    exit 1
fi

# Configuration
MIN_SDK=21
TARGET_SDK=28
TOOLCHAIN=$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64
SYSROOT=$TOOLCHAIN/sysroot

# Build for arm64-v8a
ARCH=arm64
CPU=armv8-a
CC=$TOOLCHAIN/bin/aarch64-linux-android$MIN_SDK-clang
CXX=$TOOLCHAIN/bin/aarch64-linux-android$MIN_SDK-clang++
AS=$CC
AR=$TOOLCHAIN/bin/llvm-ar
LD=$CC
RANLIB=$TOOLCHAIN/bin/llvm-ranlib
STRIP=$TOOLCHAIN/bin/llvm-strip
NM=$TOOLCHAIN/bin/llvm-nm

# Output directory
OUTPUT_DIR="FFMpegLib/src/main/assets/ffmpeg/arm64-v8a"
mkdir -p $OUTPUT_DIR

# FFmpeg source directory (you need to have FFmpeg source here)
FFMPEG_SOURCE="ffmpeg-source"
if [ ! -d "$FFMPEG_SOURCE" ]; then
    echo "Downloading FFmpeg source..."
    git clone --depth 1 --branch n7.0 https://github.com/FFmpeg/FFmpeg.git $FFMPEG_SOURCE
fi

cd $FFMPEG_SOURCE

# Clean previous builds
make clean 2>/dev/null || true

echo "Configuring FFmpeg as shared library for Android arm64-v8a..."

# Configure FFmpeg as a shared library with exported symbols
./configure \
    --prefix=$PWD/android-build \
    --target-os=android \
    --arch=$ARCH \
    --cpu=$CPU \
    --cc=$CC \
    --cxx=$CXX \
    --ar=$AR \
    --as=$AS \
    --ld=$LD \
    --ranlib=$RANLIB \
    --strip=$STRIP \
    --nm=$NM \
    --sysroot=$SYSROOT \
    --cross-prefix=aarch64-linux-android- \
    --enable-cross-compile \
    --enable-shared \
    --disable-static \
    --disable-doc \
    --disable-ffplay \
    --disable-ffprobe \
    --enable-ffmpeg \
    --disable-symver \
    --enable-small \
    --enable-gpl \
    --enable-nonfree \
    --disable-vulkan \
    --disable-libxcb \
    --disable-xlib \
    --disable-lzma \
    --disable-bzlib \
    --disable-zlib \
    --disable-iconv \
    --enable-pic \
    --enable-jni \
    --enable-mediacodec \
    --disable-asm \
    --extra-cflags="-Os -fPIC -DANDROID -D__ANDROID_API__=$MIN_SDK -fvisibility=default" \
    --extra-ldflags="-Wl,-rpath-link=$SYSROOT/usr/lib/aarch64-linux-android/$MIN_SDK -L$SYSROOT/usr/lib/aarch64-linux-android/$MIN_SDK -lc -lm -ldl -llog -landroid -Wl,--export-dynamic -Wl,-soname,libffmpeg.so" \
    --pkg-config-flags="--static"

echo "Building FFmpeg shared library..."
make -j$(nproc)

echo "Creating combined FFmpeg shared library..."

# Create a single shared library that combines all FFmpeg libraries
# This is similar to what ffmpeg-kit does
$CC -shared -o libffmpeg.so \
    -Wl,--whole-archive \
    libavcodec/libavcodec.so \
    libavformat/libavformat.so \
    libavfilter/libavfilter.so \
    libavutil/libavutil.so \
    libswscale/libswscale.so \
    libswresample/libswresample.so \
    -Wl,--no-whole-archive \
    -L$SYSROOT/usr/lib/aarch64-linux-android/$MIN_SDK \
    -lc -lm -ldl -llog -landroid \
    -Wl,--export-dynamic \
    -Wl,-soname,libffmpeg.so

# Add a wrapper function that can be called via dlsym
cat > ffmpeg_wrapper.c << 'EOF'
#include <jni.h>

// Forward declare the actual ffmpeg main function
extern int ffmpeg_main(int argc, char **argv);

// Export a wrapper that can be found via dlsym
__attribute__((visibility("default")))
int ffmpeg_run(int argc, char **argv) {
    return ffmpeg_main(argc, argv);
}

// Also export the main function directly
__attribute__((visibility("default")))
int main(int argc, char **argv) {
    return ffmpeg_main(argc, argv);
}
EOF

# Compile the wrapper
$CC -c -fPIC ffmpeg_wrapper.c -o ffmpeg_wrapper.o

# Link everything together with the wrapper
$CC -shared -o libffmpeg_jni.so \
    ffmpeg_wrapper.o \
    fftools/ffmpeg.o \
    fftools/ffmpeg_opt.o \
    fftools/ffmpeg_filter.o \
    fftools/ffmpeg_hw.o \
    fftools/ffmpeg_mux.o \
    fftools/ffmpeg_mux_init.o \
    fftools/ffmpeg_demux.o \
    fftools/ffmpeg_dec.o \
    fftools/ffmpeg_enc.o \
    fftools/cmdutils.o \
    fftools/opt_common.o \
    fftools/sync_queue.o \
    fftools/thread_queue.o \
    -L. \
    -lavformat -lavcodec -lavfilter -lavutil -lswscale -lswresample \
    -L$SYSROOT/usr/lib/aarch64-linux-android/$MIN_SDK \
    -lc -lm -ldl -llog -landroid \
    -Wl,--export-dynamic \
    -Wl,-soname,libffmpeg_jni.so \
    -Wl,--version-script,<(echo "{ global: ffmpeg_run; main; ffmpeg_main; local: *; };")

# Strip the library to reduce size
$STRIP libffmpeg_jni.so

# Copy to output directory
cp libffmpeg_jni.so ../$OUTPUT_DIR/libffmpeg.so

cd ..

echo "Build complete!"
echo "FFmpeg shared library created at: $OUTPUT_DIR/libffmpeg.so"
ls -lh $OUTPUT_DIR/libffmpeg.so

echo ""
echo "This shared library can be loaded with dlopen() and the ffmpeg_run function"
echo "can be called via dlsym() from JNI code."