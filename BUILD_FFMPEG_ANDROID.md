# Building FFmpeg for Android

This guide documents the complete process of building FFmpeg binaries for Android with full codec support, hardware acceleration, and Android 15+ 16KB page alignment compatibility.

## Prerequisites

- Android NDK 27.0.12077973 or later
- macOS/Linux build environment
- At least 10GB free disk space
- Git
- Make tools

## Build Environment Setup

```bash
# NDK Path
export NDK_PATH="/Users/mustafa/Library/Android/sdk/ndk/27.0.12077973"

# Minimum API level
export MIN_API=21

# Build directory
export BUILD_DIR="/tmp/ffmpeg-full-build"
```

## Download FFmpeg Source

```bash
mkdir -p $BUILD_DIR
cd $BUILD_DIR

# Clone FFmpeg source (latest stable branch)
git clone https://github.com/FFmpeg/FFmpeg.git ffmpeg-source
cd ffmpeg-source
git checkout release/6.0
```

## Build Script

Create `build-ffmpeg-android.sh`:

```bash
#!/bin/bash
set -e

NDK_PATH="/Users/mustafa/Library/Android/sdk/ndk/27.0.12077973"
MIN_API=21
FFMPEG_SOURCE="/tmp/ffmpeg-full-build/ffmpeg-source"
OUTPUT_BASE="/Users/mustafa/AndroidStudioProjects/FfmpegLib/FFMpegLib/src/main/assets/ffmpeg"

# Function to build for a specific architecture
build_arch() {
    local ARCH=$1
    local ABI=$2
    local TOOLCHAIN_PREFIX=$3
    local CPU=$4
    local EXTRA_CFLAGS=$5
    local EXTRA_LDFLAGS=$6
    
    echo "Building FFmpeg for $ABI..."
    
    TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64"
    SYSROOT="$TOOLCHAIN/sysroot"
    CC="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang"
    CXX="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang++"
    AR="$TOOLCHAIN/bin/llvm-ar"
    AS="$CC"
    NM="$TOOLCHAIN/bin/llvm-nm"
    RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    STRIP="$TOOLCHAIN/bin/llvm-strip"
    
    cd "$FFMPEG_SOURCE"
    make clean 2>/dev/null || true
    
    ./configure \
        --prefix=/tmp/ffmpeg-full-build/output \
        --target-os=android \
        --arch=$ARCH \
        --cpu=$CPU \
        --enable-cross-compile \
        --cc="$CC" \
        --cxx="$CXX" \
        --ar="$AR" \
        --as="$AS" \
        --nm="$NM" \
        --ranlib="$RANLIB" \
        --strip="$STRIP" \
        --sysroot="$SYSROOT" \
        --extra-cflags="$EXTRA_CFLAGS" \
        --extra-ldflags="$EXTRA_LDFLAGS" \
        --enable-static \
        --disable-shared \
        --disable-doc \
        --disable-ffplay \
        --disable-ffprobe \
        --disable-avdevice \
        --disable-symver \
        --enable-gpl \
        --enable-pic \
        --disable-debug \
        --enable-jni \
        --enable-mediacodec \
        --enable-decoder=h264 \
        --enable-decoder=h264_mediacodec \
        --enable-decoder=hevc \
        --enable-decoder=hevc_mediacodec \
        --enable-decoder=mpeg4 \
        --enable-decoder=mpeg4_mediacodec \
        --enable-decoder=vp8 \
        --enable-decoder=vp8_mediacodec \
        --enable-decoder=vp9 \
        --enable-decoder=vp9_mediacodec \
        --enable-decoder=aac \
        --enable-decoder=mp3 \
        --enable-decoder=opus \
        --enable-decoder=vorbis \
        --enable-decoder=flac \
        --enable-encoder=mpeg4 \
        --enable-encoder=aac \
        --enable-encoder=mjpeg \
        --enable-encoder=png \
        --enable-muxer=mp4 \
        --enable-muxer=webm \
        --enable-muxer=mkv \
        --enable-muxer=mov \
        --enable-muxer=avi \
        --enable-muxer=flv \
        --enable-demuxer=mov \
        --enable-demuxer=mp4 \
        --enable-demuxer=avi \
        --enable-demuxer=mkv \
        --enable-demuxer=webm \
        --enable-demuxer=flv \
        --enable-protocol=file \
        --enable-protocol=http \
        --enable-protocol=https \
        --enable-filter=scale \
        --enable-filter=overlay \
        --enable-filter=crop \
        --enable-filter=rotate \
        --enable-filter=transpose \
        --enable-filter=fps \
        --enable-filter=format \
        --enable-filter=aresample \
        --enable-filter=volume \
        --enable-zlib
    
    make -j8
    
    mkdir -p "$OUTPUT_BASE/$ABI"
    cp ffmpeg "$OUTPUT_BASE/$ABI/libffmpeg.so"
    chmod 755 "$OUTPUT_BASE/$ABI/libffmpeg.so"
    
    SIZE=$(ls -lh "$OUTPUT_BASE/$ABI/libffmpeg.so" | awk '{print $5}')
    echo "✓ Built FFmpeg for $ABI - Size: $SIZE"
}

# Build for arm64-v8a with 16KB alignment
ARCH="aarch64"
ABI="arm64-v8a"
CFLAGS="-O3 -fPIC -DANDROID -Wno-deprecated"
LDFLAGS="-Wl,-rpath-link=$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/$MIN_API"
LDFLAGS="$LDFLAGS -L$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/$MIN_API"
LDFLAGS="$LDFLAGS -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
LDFLAGS="$LDFLAGS $NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/lib/clang/18/lib/linux/libclang_rt.builtins-aarch64-android.a"
LDFLAGS="$LDFLAGS -lc -lm -ldl -llog -static-libgcc"

build_arch "aarch64" "arm64-v8a" "aarch64-linux-android" "armv8-a" "$CFLAGS" "$LDFLAGS"

# Build for armeabi-v7a with 16KB alignment
ARCH="arm"
ABI="armeabi-v7a"
CFLAGS="-O3 -fPIC -DANDROID -Wno-deprecated -mfpu=neon -mfloat-abi=softfp"
LDFLAGS="-Wl,-rpath-link=$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/arm-linux-androideabi/$MIN_API"
LDFLAGS="$LDFLAGS -L$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/arm-linux-androideabi/$MIN_API"
LDFLAGS="$LDFLAGS -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
LDFLAGS="$LDFLAGS $NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/lib/clang/18/lib/linux/libclang_rt.builtins-arm-android.a"
LDFLAGS="$LDFLAGS -lc -lm -ldl -llog -static-libgcc"

build_arch "arm" "armeabi-v7a" "armv7a-linux-androideabi" "armv7-a" "$CFLAGS" "$LDFLAGS"

echo "FFmpeg build complete for all architectures!"
```

## Key Configuration Options

### Essential Codecs and Features

```bash
# Video Decoders (with hardware acceleration)
--enable-decoder=h264
--enable-decoder=h264_mediacodec    # Hardware accelerated
--enable-decoder=hevc
--enable-decoder=hevc_mediacodec    # Hardware accelerated
--enable-decoder=mpeg4
--enable-decoder=mpeg4_mediacodec   # Hardware accelerated
--enable-decoder=vp8
--enable-decoder=vp8_mediacodec     # Hardware accelerated
--enable-decoder=vp9
--enable-decoder=vp9_mediacodec     # Hardware accelerated

# Audio Decoders
--enable-decoder=aac
--enable-decoder=mp3
--enable-decoder=opus
--enable-decoder=vorbis
--enable-decoder=flac

# Video Encoders
--enable-encoder=mpeg4              # Software encoder
--enable-encoder=aac
--enable-encoder=mjpeg
--enable-encoder=png

# Container Formats
--enable-muxer=mp4
--enable-muxer=webm
--enable-muxer=mkv
--enable-muxer=mov
--enable-muxer=avi
--enable-muxer=flv

# Protocols
--enable-protocol=file
--enable-protocol=http
--enable-protocol=https

# Essential Filters
--enable-filter=scale
--enable-filter=overlay
--enable-filter=crop
--enable-filter=rotate
--enable-filter=transpose
--enable-filter=fps
--enable-filter=format
--enable-filter=aresample
--enable-filter=volume
```

### Android-Specific Options

```bash
--enable-jni                        # JNI support for MediaCodec
--enable-mediacodec                 # Hardware acceleration via MediaCodec
--target-os=android                 # Target Android OS
--enable-pic                        # Position Independent Code
--disable-symver                    # Disable symbol versioning (not supported on Android)
```

### Android 15+ 16KB Page Alignment

Critical flags for Android 15+ compatibility:

```bash
--extra-ldflags="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
```

## Fixing Common Build Issues

### 1. Undefined Soft-Float Symbols

If you encounter errors like `__extenddftf2`, `__floatunsitf`, add the compiler runtime library:

```bash
# For arm64-v8a
$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/lib/clang/18/lib/linux/libclang_rt.builtins-aarch64-android.a

# For armeabi-v7a
$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/lib/clang/18/lib/linux/libclang_rt.builtins-arm-android.a
```

### 2. 16KB Page Alignment Warning

If you see "APK is not compatible with 16 KB devices", ensure:

1. FFmpeg binaries are built with alignment flags
2. APK is aligned using zipalign:

```bash
# Verify alignment
zipalign -c -v -p 16384 app-debug.apk

# Align APK if needed
zipalign -p -f -v 16384 input.apk output.apk
```

### 3. Gradle Configuration

Add to `app/build.gradle.kts`:

```kotlin
android {
    packaging {
        jniLibs {
            // Use page alignment for 16KB devices
            useLegacyPackaging = false
        }
    }
}
```

## Binary Sizes

Typical sizes for full-featured FFmpeg builds:

- **arm64-v8a**: ~21MB
- **armeabi-v7a**: ~19MB

## Integration with Android Project

1. Place binaries in: `app/src/main/assets/ffmpeg/{ABI}/libffmpeg.so`
2. Use JNI wrapper to execute FFmpeg commands
3. Extract and execute at runtime using popen() to bypass Android 10+ restrictions

## Verification

### Check FFmpeg Features

```bash
# List enabled decoders
./ffmpeg -decoders

# List enabled encoders
./ffmpeg -encoders

# List enabled formats
./ffmpeg -formats

# List enabled protocols
./ffmpeg -protocols

# List enabled filters
./ffmpeg -filters
```

### Verify 16KB Alignment

```bash
# Using readelf
readelf -l libffmpeg.so | grep LOAD

# Check alignment values - should show 0x4000 (16384)
```

## Troubleshooting

### Build Fails with "Unknown option"

Some options may not be available in older FFmpeg versions. Check available options:

```bash
./configure --help
```

### Missing Hardware Acceleration

Ensure these are enabled:
- `--enable-jni`
- `--enable-mediacodec`
- Specific mediacodec decoders (e.g., `h264_mediacodec`)

### Android 10+ Execution Issues

Use JNI wrapper with popen() instead of direct execution. See the main project README for implementation details.

## Resources

- [FFmpeg Documentation](https://ffmpeg.org/documentation.html)
- [Android NDK Guide](https://developer.android.com/ndk/guides)
- [Android 16KB Page Size](https://developer.android.com/guide/practices/page-sizes)
- [MediaCodec Integration](https://developer.android.com/reference/android/media/MediaCodec)

## License

FFmpeg is licensed under the GPL. When using `--enable-gpl`, your application must comply with GPL requirements.