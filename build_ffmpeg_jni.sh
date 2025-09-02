#!/bin/bash

# Build FFmpeg as a shared library for JNI integration
# This allows calling FFmpeg functions directly from Java, bypassing Android 10+ restrictions

set -e

echo "Building FFmpeg as JNI library for Android..."

# Configuration
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
MIN_SDK=21

# Paths
BUILD_DIR="/tmp/ffmpeg-jni-build"
SOURCE_DIR="/tmp/ffmpeg-source"
OUTPUT_DIR="/Users/mustafa/AndroidStudioProjects/FfmpegLib/FFMpegLib/src/main/jniLibs"
JNI_DIR="/Users/mustafa/AndroidStudioProjects/FfmpegLib/FFMpegLib/src/main/cpp"

# Create directories
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

echo "Configuring FFmpeg as shared library..."

# Configure FFmpeg as a shared library (not executable)
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
    --extra-cflags="-O3 -fPIC -DANDROID -Wno-deprecated -fvisibility=default" \
    --extra-ldflags="-Wl,-rpath-link=$SYSROOT/usr/lib/aarch64-linux-android/$MIN_SDK -L$SYSROOT/usr/lib/aarch64-linux-android/$MIN_SDK $TOOLCHAIN/lib/clang/18/lib/linux/libclang_rt.builtins-aarch64-android.a -lc -lm -ldl -llog -shared" \
    --disable-static \
    --enable-shared \
    --disable-doc \
    --disable-programs \
    --disable-symver \
    --enable-small \
    --enable-gpl \
    --enable-pic

echo "Building FFmpeg libraries..."
make -j$(sysctl -n hw.ncpu)

echo "Creating combined library..."

# Combine all FFmpeg libraries into one
cd "$BUILD_DIR"
mkdir -p combined

# Extract all object files
for lib in $SOURCE_DIR/lib*.so; do
    if [ -f "$lib" ]; then
        echo "Extracting $(basename $lib)..."
        $AR x "$lib"
    fi
done

# Create the JNI wrapper object file
cat > "$JNI_DIR/ffmpeg_jni.c" << 'EOF'
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

#define LOG_TAG "FFmpegJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// FFmpeg's main function
extern int ffmpeg_main(int argc, char **argv);

JNIEXPORT jint JNICALL
Java_com_mzgs_ffmpeglib_FFmpegJNI_nativeRunFFmpeg(JNIEnv *env, jobject thiz, jobjectArray args) {
    int argc = (*env)->GetArrayLength(env, args);
    char **argv = (char **)malloc(sizeof(char *) * (argc + 1));
    
    // Program name
    argv[0] = strdup("ffmpeg");
    
    // Copy arguments
    for (int i = 0; i < argc; i++) {
        jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *str = (*env)->GetStringUTFChars(env, jstr, NULL);
        argv[i + 1] = strdup(str);
        (*env)->ReleaseStringUTFChars(env, jstr, str);
    }
    
    LOGI("Running FFmpeg with %d arguments", argc + 1);
    
    // Call FFmpeg main
    int result = ffmpeg_main(argc + 1, argv);
    
    // Clean up
    for (int i = 0; i <= argc; i++) {
        free(argv[i]);
    }
    free(argv);
    
    return result;
}
EOF

# Compile the JNI wrapper
$CC -c "$JNI_DIR/ffmpeg_jni.c" -o ffmpeg_jni.o \
    -I"$SYSROOT/usr/include" \
    -I"$SOURCE_DIR" \
    -fPIC -DANDROID

# Create combined shared library
$CC -shared -o libffmpeg_jni.so \
    *.o \
    -L"$SYSROOT/usr/lib/aarch64-linux-android/$MIN_SDK" \
    -lc -lm -ldl -llog -lz \
    $TOOLCHAIN/lib/clang/18/lib/linux/libclang_rt.builtins-aarch64-android.a

# Strip debug symbols
$STRIP libffmpeg_jni.so

# Copy to JNI libs directory
cp libffmpeg_jni.so "$OUTPUT_DIR/arm64-v8a/"

echo "Checking library..."
file "$OUTPUT_DIR/arm64-v8a/libffmpeg_jni.so"
ls -lh "$OUTPUT_DIR/arm64-v8a/libffmpeg_jni.so"

echo ""
echo "✅ FFmpeg JNI library built successfully!"
echo "Location: $OUTPUT_DIR/arm64-v8a/libffmpeg_jni.so"
echo ""
echo "This library can be loaded with System.loadLibrary(\"ffmpeg_jni\")"
echo "and will work on all Android versions including 10+"