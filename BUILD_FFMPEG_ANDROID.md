# Building FFmpeg for Android - Full GPL Build

This guide documents the complete process of building FFmpeg binaries for Android with **FULL codec support** including all encoders/decoders, external libraries (libmp3lame, x264, x265, vpx, opus, etc.), hardware acceleration, and Android 15+ 16KB page alignment compatibility.

## Prerequisites

- Android NDK 27.0.12077973 or later
- macOS/Linux build environment
- At least 20GB free disk space (for building external libraries)
- Git
- Make tools
- Autoconf, Automake, CMake (for building external libraries)
- pkg-config

## Build Environment Setup

```bash
# NDK Path
export NDK_PATH="/Users/mustafa/Library/Android/sdk/ndk/27.0.12077973"

# Minimum API level
export MIN_API=21

# Build directory
export BUILD_DIR="/tmp/ffmpeg-full-build"
```

## Building External Libraries First

### 1. Build libmp3lame (MP3 encoding)

```bash
cd $BUILD_DIR
git clone https://github.com/lameproject/lame.git
cd lame
git checkout v3.100

# Build for arm64-v8a
export TOOLCHAIN=$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64
export CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang
export CXX=$TOOLCHAIN/bin/aarch64-linux-android21-clang++
export AR=$TOOLCHAIN/bin/llvm-ar
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
export STRIP=$TOOLCHAIN/bin/llvm-strip

./configure \
    --host=aarch64-linux-android \
    --prefix=$BUILD_DIR/external/arm64-v8a \
    --disable-shared \
    --enable-static \
    --disable-frontend

make clean
make -j8
make install
```

### 2. Build x264 (H.264 encoding)

```bash
cd $BUILD_DIR
git clone https://code.videolan.org/videolan/x264.git
cd x264

# Build for arm64-v8a
./configure \
    --host=aarch64-linux-android \
    --cross-prefix=$TOOLCHAIN/bin/aarch64-linux-android- \
    --sysroot=$TOOLCHAIN/sysroot \
    --prefix=$BUILD_DIR/external/arm64-v8a \
    --enable-static \
    --enable-pic \
    --disable-cli

make clean
make -j8
make install
```

### 3. Build x265 (H.265/HEVC encoding)

```bash
cd $BUILD_DIR
git clone https://github.com/videolan/x265.git
cd x265/build/linux

cmake -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE=$NDK_PATH/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-21 \
    -DCMAKE_INSTALL_PREFIX=$BUILD_DIR/external/arm64-v8a \
    -DENABLE_SHARED=OFF \
    -DENABLE_CLI=OFF \
    ../../source

make -j8
make install
```

### 4. Build libvpx (VP8/VP9 encoding)

```bash
cd $BUILD_DIR
git clone https://chromium.googlesource.com/webm/libvpx.git
cd libvpx

# Build for arm64-v8a
./configure \
    --target=arm64-android-gcc \
    --prefix=$BUILD_DIR/external/arm64-v8a \
    --disable-examples \
    --disable-docs \
    --disable-unit-tests \
    --enable-static \
    --disable-shared \
    --enable-vp8 \
    --enable-vp9

make clean
make -j8
make install
```

### 5. Build libopus (Opus audio encoding)

```bash
cd $BUILD_DIR
git clone https://github.com/xiph/opus.git
cd opus

./autogen.sh
./configure \
    --host=aarch64-linux-android \
    --prefix=$BUILD_DIR/external/arm64-v8a \
    --disable-shared \
    --enable-static \
    --disable-doc

make clean
make -j8
make install
```

### 6. Build libvorbis (Vorbis audio encoding)

```bash
cd $BUILD_DIR
# First build libogg (dependency)
git clone https://github.com/xiph/ogg.git
cd ogg
./autogen.sh
./configure \
    --host=aarch64-linux-android \
    --prefix=$BUILD_DIR/external/arm64-v8a \
    --disable-shared \
    --enable-static

make clean
make -j8
make install

# Now build libvorbis
cd $BUILD_DIR
git clone https://github.com/xiph/vorbis.git
cd vorbis
./autogen.sh
PKG_CONFIG_PATH=$BUILD_DIR/external/arm64-v8a/lib/pkgconfig \
./configure \
    --host=aarch64-linux-android \
    --prefix=$BUILD_DIR/external/arm64-v8a \
    --disable-shared \
    --enable-static \
    --with-ogg=$BUILD_DIR/external/arm64-v8a

make clean
make -j8
make install
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
    
    # Set PKG_CONFIG_PATH for external libraries
    export PKG_CONFIG_PATH="$BUILD_DIR/external/$ABI/lib/pkgconfig"
    
    # Add include and lib paths for external libraries
    EXTRA_CFLAGS="$EXTRA_CFLAGS -I$BUILD_DIR/external/$ABI/include"
    EXTRA_LDFLAGS="$EXTRA_LDFLAGS -L$BUILD_DIR/external/$ABI/lib"
    
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
        --pkg-config="pkg-config" \
        --enable-static \
        --disable-shared \
        --disable-doc \
        --disable-ffplay \
        --disable-ffprobe \
        --disable-avdevice \
        --disable-symver \
        --enable-gpl \
        --enable-version3 \
        --enable-pic \
        --disable-debug \
        --enable-jni \
        --enable-mediacodec \
        --enable-zlib \
        \
        `# External Libraries` \
        --enable-libmp3lame \
        --enable-libx264 \
        --enable-libx265 \
        --enable-libvpx \
        --enable-libopus \
        --enable-libvorbis \
        \
        `# Video Decoders (Software + Hardware)` \
        --enable-decoder=h264 \
        --enable-decoder=h264_mediacodec \
        --enable-decoder=hevc \
        --enable-decoder=hevc_mediacodec \
        --enable-decoder=mpeg4 \
        --enable-decoder=mpeg4_mediacodec \
        --enable-decoder=mpeg2video \
        --enable-decoder=vp8 \
        --enable-decoder=vp8_mediacodec \
        --enable-decoder=vp9 \
        --enable-decoder=vp9_mediacodec \
        --enable-decoder=av1 \
        --enable-decoder=mjpeg \
        --enable-decoder=png \
        --enable-decoder=gif \
        --enable-decoder=webp \
        \
        `# Audio Decoders` \
        --enable-decoder=aac \
        --enable-decoder=mp3 \
        --enable-decoder=opus \
        --enable-decoder=vorbis \
        --enable-decoder=flac \
        --enable-decoder=ac3 \
        --enable-decoder=eac3 \
        --enable-decoder=dts \
        --enable-decoder=truehd \
        --enable-decoder=pcm_s16le \
        --enable-decoder=pcm_s16be \
        --enable-decoder=pcm_f32le \
        \
        `# Video Encoders (Software + External Libraries)` \
        --enable-encoder=libx264 \
        --enable-encoder=libx265 \
        --enable-encoder=libvpx_vp8 \
        --enable-encoder=libvpx_vp9 \
        --enable-encoder=mpeg4 \
        --enable-encoder=mpeg2video \
        --enable-encoder=mjpeg \
        --enable-encoder=png \
        --enable-encoder=gif \
        --enable-encoder=webp \
        \
        `# Audio Encoders` \
        --enable-encoder=libmp3lame \
        --enable-encoder=aac \
        --enable-encoder=libopus \
        --enable-encoder=libvorbis \
        --enable-encoder=flac \
        --enable-encoder=ac3 \
        --enable-encoder=eac3 \
        --enable-encoder=pcm_s16le \
        --enable-encoder=pcm_s16be \
        --enable-encoder=pcm_f32le \
        \
        `# Muxers (Container Formats)` \
        --enable-muxer=mp4 \
        --enable-muxer=mp3 \
        --enable-muxer=webm \
        --enable-muxer=mkv \
        --enable-muxer=mov \
        --enable-muxer=avi \
        --enable-muxer=flv \
        --enable-muxer=wav \
        --enable-muxer=flac \
        --enable-muxer=ogg \
        --enable-muxer=mpegts \
        --enable-muxer=hls \
        --enable-muxer=dash \
        --enable-muxer=image2 \
        --enable-muxer=mjpeg \
        \
        `# Demuxers` \
        --enable-demuxer=mov \
        --enable-demuxer=mp4 \
        --enable-demuxer=mp3 \
        --enable-demuxer=avi \
        --enable-demuxer=mkv \
        --enable-demuxer=webm \
        --enable-demuxer=flv \
        --enable-demuxer=wav \
        --enable-demuxer=flac \
        --enable-demuxer=ogg \
        --enable-demuxer=mpegts \
        --enable-demuxer=hls \
        --enable-demuxer=dash \
        --enable-demuxer=image2 \
        --enable-demuxer=mjpeg \
        --enable-demuxer=concat \
        \
        `# Parsers (Important for streaming)` \
        --enable-parser=h264 \
        --enable-parser=hevc \
        --enable-parser=mpeg4video \
        --enable-parser=vp8 \
        --enable-parser=vp9 \
        --enable-parser=aac \
        --enable-parser=mp3 \
        --enable-parser=opus \
        --enable-parser=vorbis \
        --enable-parser=flac \
        \
        `# Protocols` \
        --enable-protocol=file \
        --enable-protocol=http \
        --enable-protocol=https \
        --enable-protocol=rtmp \
        --enable-protocol=rtmps \
        --enable-protocol=hls \
        --enable-protocol=concat \
        --enable-protocol=data \
        \
        `# Filters (All important filters)` \
        --enable-filter=scale \
        --enable-filter=overlay \
        --enable-filter=crop \
        --enable-filter=rotate \
        --enable-filter=transpose \
        --enable-filter=fps \
        --enable-filter=format \
        --enable-filter=aresample \
        --enable-filter=volume \
        --enable-filter=pad \
        --enable-filter=trim \
        --enable-filter=concat \
        --enable-filter=split \
        --enable-filter=vflip \
        --enable-filter=hflip \
        --enable-filter=setpts \
        --enable-filter=adelay \
        --enable-filter=atempo \
        --enable-filter=aformat \
        --enable-filter=amix \
        --enable-filter=amerge \
        --enable-filter=channelmap \
        --enable-filter=channelsplit \
        --enable-filter=compand \
        --enable-filter=equalizer \
        --enable-filter=highpass \
        --enable-filter=lowpass
    
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

### Minimal Build (Current)
- **arm64-v8a**: ~21MB
- **armeabi-v7a**: ~19MB

### Full GPL Build (With All External Libraries)
- **arm64-v8a**: ~50-60MB
- **armeabi-v7a**: ~45-55MB

The size increase is due to:
- libmp3lame: ~1-2MB
- libx264: ~2-3MB
- libx265: ~5-8MB
- libvpx: ~3-4MB
- libopus: ~1MB
- libvorbis + libogg: ~1-2MB
- Additional codecs and filters: ~10-15MB

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
are there any other video. audio
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