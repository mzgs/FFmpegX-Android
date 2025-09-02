#!/bin/bash

# FFmpeg Full Build Script for Android
# This builds FFmpeg with ALL codecs and features (like ffmpeg-kit full-gpl)

set -e

# Configuration
FFMPEG_VERSION="6.0"
NDK_PATH="${ANDROID_HOME}/ndk/27.0.12077973"
MIN_API=21
OUTPUT_DIR="$(pwd)/FFMpegLib/src/main/assets/ffmpeg"
BUILD_DIR="/tmp/ffmpeg-android-full-build"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}================================================${NC}"
echo -e "${GREEN}FFmpeg Full Build Script for Android${NC}"
echo -e "${GREEN}Building with ALL codecs (x264, x265, VP9, etc.)${NC}"
echo -e "${GREEN}================================================${NC}"

# Check NDK
if [ ! -d "$NDK_PATH" ]; then
    echo -e "${RED}Error: Android NDK not found at $NDK_PATH${NC}"
    exit 1
fi

# Create build directory
rm -rf $BUILD_DIR
mkdir -p $BUILD_DIR
cd $BUILD_DIR

# Function to build for a specific architecture
build_ffmpeg() {
    local ARCH=$1
    local ABI=$2
    local CPU=$3
    local TOOLCHAIN_PREFIX=$4
    local EXTRA_CFLAGS=$5
    local EXTRA_LDFLAGS=$6
    
    echo -e "${YELLOW}Building FFmpeg for $ABI...${NC}"
    
    # Set up toolchain
    TOOLCHAIN=$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64
    SYSROOT=$TOOLCHAIN/sysroot
    CC=$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang
    CXX=$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang++
    AR=$TOOLCHAIN/bin/llvm-ar
    AS=$CC
    NM=$TOOLCHAIN/bin/llvm-nm
    RANLIB=$TOOLCHAIN/bin/llvm-ranlib
    STRIP=$TOOLCHAIN/bin/llvm-strip
    
    # Create output directory
    PREFIX=$BUILD_DIR/output/$ABI
    mkdir -p $PREFIX
    
    # First, build required libraries for full codec support
    
    # 1. Build x264 (H.264 encoder)
    echo -e "${GREEN}Building x264...${NC}"
    if [ ! -d "x264" ]; then
        git clone --depth 1 https://code.videolan.org/videolan/x264.git
    fi
    cd x264
    ./configure \
        --prefix=$PREFIX \
        --host=${TOOLCHAIN_PREFIX} \
        --cross-prefix="${TOOLCHAIN}/bin/${TOOLCHAIN_PREFIX}${MIN_API}-" \
        --sysroot=$SYSROOT \
        --enable-static \
        --disable-shared \
        --disable-cli \
        --enable-pic \
        --extra-cflags="$EXTRA_CFLAGS" \
        --extra-ldflags="$EXTRA_LDFLAGS"
    make clean
    make -j$(nproc)
    make install
    cd ..
    
    # 2. Build x265 (H.265/HEVC encoder)
    echo -e "${GREEN}Building x265...${NC}"
    if [ ! -d "x265" ]; then
        git clone --depth 1 https://bitbucket.org/multicoreware/x265_git.git x265
    fi
    cd x265/build/android
    cmake -G "Unix Makefiles" \
        -DCMAKE_SYSTEM_NAME=Android \
        -DCMAKE_SYSTEM_VERSION=$MIN_API \
        -DCMAKE_ANDROID_ARCH_ABI=$ABI \
        -DCMAKE_ANDROID_NDK=$NDK_PATH \
        -DCMAKE_ANDROID_STL_TYPE=c++_static \
        -DCMAKE_INSTALL_PREFIX=$PREFIX \
        -DENABLE_SHARED=OFF \
        -DENABLE_CLI=OFF \
        ../../source
    make clean
    make -j$(nproc)
    make install
    cd ../../../
    
    # 3. Build libvpx (VP8/VP9 encoder)
    echo -e "${GREEN}Building libvpx...${NC}"
    if [ ! -d "libvpx" ]; then
        git clone --depth 1 https://chromium.googlesource.com/webm/libvpx
    fi
    cd libvpx
    ./configure \
        --prefix=$PREFIX \
        --target=${ARCH}-android-gcc \
        --disable-examples \
        --disable-docs \
        --disable-tools \
        --disable-unit-tests \
        --enable-static \
        --disable-shared \
        --enable-pic \
        --extra-cflags="$EXTRA_CFLAGS"
    make clean
    make -j$(nproc)
    make install
    cd ..
    
    # 4. Build fdk-aac (High quality AAC encoder)
    echo -e "${GREEN}Building fdk-aac...${NC}"
    if [ ! -d "fdk-aac" ]; then
        git clone --depth 1 https://github.com/mstorsjo/fdk-aac.git
    fi
    cd fdk-aac
    autoreconf -fiv
    ./configure \
        --prefix=$PREFIX \
        --host=${TOOLCHAIN_PREFIX} \
        --with-sysroot=$SYSROOT \
        --enable-static \
        --disable-shared \
        CC=$CC \
        CXX=$CXX \
        CFLAGS="$EXTRA_CFLAGS" \
        LDFLAGS="$EXTRA_LDFLAGS"
    make clean
    make -j$(nproc)
    make install
    cd ..
    
    # 5. Build lame (MP3 encoder)
    echo -e "${GREEN}Building lame...${NC}"
    if [ ! -d "lame-3.100" ]; then
        wget -q https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz
        tar xzf lame-3.100.tar.gz
    fi
    cd lame-3.100
    ./configure \
        --prefix=$PREFIX \
        --host=${TOOLCHAIN_PREFIX} \
        --enable-static \
        --disable-shared \
        --disable-frontend \
        CC=$CC \
        CFLAGS="$EXTRA_CFLAGS" \
        LDFLAGS="$EXTRA_LDFLAGS"
    make clean
    make -j$(nproc)
    make install
    cd ..
    
    # 6. Build opus (Audio codec)
    echo -e "${GREEN}Building opus...${NC}"
    if [ ! -d "opus" ]; then
        git clone --depth 1 https://github.com/xiph/opus.git
    fi
    cd opus
    ./autogen.sh
    ./configure \
        --prefix=$PREFIX \
        --host=${TOOLCHAIN_PREFIX} \
        --enable-static \
        --disable-shared \
        --disable-doc \
        CC=$CC \
        CFLAGS="$EXTRA_CFLAGS" \
        LDFLAGS="$EXTRA_LDFLAGS"
    make clean
    make -j$(nproc)
    make install
    cd ..
    
    # Now build FFmpeg with all the libraries
    echo -e "${GREEN}Building FFmpeg with all codecs...${NC}"
    if [ ! -d "ffmpeg-$FFMPEG_VERSION" ]; then
        wget -q https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz
        tar xf ffmpeg-$FFMPEG_VERSION.tar.xz
    fi
    cd ffmpeg-$FFMPEG_VERSION
    
    # Configure FFmpeg with ALL features
    PKG_CONFIG_PATH=$PREFIX/lib/pkgconfig \
    ./configure \
        --prefix=$PREFIX \
        --target-os=android \
        --arch=$ARCH \
        --cpu=$CPU \
        --enable-cross-compile \
        --cc=$CC \
        --cxx=$CXX \
        --ar=$AR \
        --as=$AS \
        --nm=$NM \
        --ranlib=$RANLIB \
        --strip=$STRIP \
        --sysroot=$SYSROOT \
        --extra-cflags="-I$PREFIX/include $EXTRA_CFLAGS" \
        --extra-ldflags="-L$PREFIX/lib $EXTRA_LDFLAGS -lm -lz -ldl" \
        --enable-static \
        --disable-shared \
        --disable-doc \
        --disable-ffplay \
        --disable-ffprobe \
        --disable-symver \
        --enable-small \
        --enable-gpl \
        --enable-version3 \
        --enable-nonfree \
        --enable-pic \
        --enable-jni \
        --enable-mediacodec \
        --enable-libx264 \
        --enable-libx265 \
        --enable-libvpx \
        --enable-libfdk-aac \
        --enable-libmp3lame \
        --enable-libopus \
        --enable-encoder=libx264 \
        --enable-encoder=libx265 \
        --enable-encoder=libvpx_vp8 \
        --enable-encoder=libvpx_vp9 \
        --enable-encoder=libfdk_aac \
        --enable-encoder=libmp3lame \
        --enable-encoder=libopus \
        --enable-decoder=h264 \
        --enable-decoder=hevc \
        --enable-decoder=vp8 \
        --enable-decoder=vp9 \
        --enable-decoder=aac \
        --enable-decoder=mp3 \
        --enable-decoder=opus \
        --enable-filter=scale \
        --enable-filter=overlay \
        --enable-filter=crop \
        --enable-filter=rotate \
        --enable-filter=fps \
        --enable-filter=format \
        --enable-filter=aresample \
        --enable-filter=volume \
        --enable-protocol=file \
        --enable-protocol=http \
        --enable-protocol=https \
        --enable-protocol=rtmp \
        --enable-protocol=hls \
        --enable-muxer=mp4 \
        --enable-muxer=webm \
        --enable-muxer=mkv \
        --enable-muxer=mov \
        --enable-muxer=avi \
        --enable-muxer=flv \
        --enable-muxer=mpegts \
        --enable-muxer=hls \
        --enable-demuxer=mov \
        --enable-demuxer=mp4 \
        --enable-demuxer=avi \
        --enable-demuxer=mkv \
        --enable-demuxer=webm \
        --enable-demuxer=flv \
        --enable-demuxer=mpegts \
        --enable-demuxer=hls
    
    make clean
    make -j$(nproc)
    make install
    
    # Copy the binary to assets
    mkdir -p $OUTPUT_DIR/$ABI
    cp $PREFIX/bin/ffmpeg $OUTPUT_DIR/$ABI/libffmpeg.so
    chmod 755 $OUTPUT_DIR/$ABI/libffmpeg.so
    
    # Show binary size
    SIZE=$(ls -lh $OUTPUT_DIR/$ABI/libffmpeg.so | awk '{print $5}')
    echo -e "${GREEN}✓ Built FFmpeg for $ABI - Size: $SIZE${NC}"
    
    cd ..
}

# Build for each architecture
build_ffmpeg "aarch64" "arm64-v8a" "armv8-a" "aarch64-linux-android" \
    "-O3 -fPIC -DANDROID" \
    "-Wl,-rpath-link=$SYSROOT/usr/lib/aarch64-linux-android/$MIN_API"

build_ffmpeg "arm" "armeabi-v7a" "armv7-a" "armv7a-linux-androideabi" \
    "-O3 -fPIC -DANDROID -mfpu=neon -mfloat-abi=softfp" \
    "-Wl,-rpath-link=$SYSROOT/usr/lib/arm-linux-androideabi/$MIN_API"

echo -e "${GREEN}================================================${NC}"
echo -e "${GREEN}FFmpeg Full Build Complete!${NC}"
echo -e "${GREEN}Binaries are in: $OUTPUT_DIR${NC}"
echo -e "${GREEN}================================================${NC}"
echo ""
echo -e "${YELLOW}Features included:${NC}"
echo "✓ H.264 encoding (libx264) with presets"
echo "✓ H.265/HEVC encoding (libx265)"
echo "✓ VP8/VP9 encoding (libvpx)"
echo "✓ High quality AAC (libfdk-aac)"
echo "✓ MP3 encoding (libmp3lame)"
echo "✓ Opus audio (libopus)"
echo "✓ Hardware acceleration (MediaCodec)"
echo "✓ All major filters"
echo "✓ All major formats"
echo ""
echo -e "${YELLOW}Size comparison:${NC}"
ls -lh $OUTPUT_DIR/*/libffmpeg.so