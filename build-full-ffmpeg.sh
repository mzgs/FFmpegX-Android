#!/bin/bash
set -e

# ================================================
# Full FFmpeg Build Script for Android
# Includes: Full GPL, MP3, AAC, H264, H265, and all codecs
# ================================================

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Configuration
NDK_PATH="/Users/mustafa/Library/Android/sdk/ndk/27.0.12077973"
MIN_API=21
BUILD_DIR="/tmp/ffmpeg-full-build"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
OUTPUT_BASE="$SCRIPT_DIR/FFMpegLib/src/main/assets/ffmpeg"
JOBS=8

echo -e "${MAGENTA}================================================${NC}"
echo -e "${MAGENTA}     Full FFmpeg Build for Android${NC}"
echo -e "${MAGENTA}     GPL + MP3 + All Codecs${NC}"
echo -e "${MAGENTA}================================================${NC}"
echo ""
echo -e "${CYAN}This will build FFmpeg with:${NC}"
echo -e "${GREEN}✓ Full GPL license${NC}"
echo -e "${GREEN}✓ MP3 encoding (libmp3lame)${NC}"
echo -e "${GREEN}✓ AAC encoding${NC}"
echo -e "${GREEN}✓ H.264/H.265 decoders${NC}"
echo -e "${GREEN}✓ VP8/VP9 decoders${NC}"
echo -e "${GREEN}✓ Hardware acceleration (MediaCodec)${NC}"
echo -e "${GREEN}✓ All common formats and protocols${NC}"
echo -e "${GREEN}✓ Android 15+ 16KB page alignment${NC}"
echo ""
echo -e "${YELLOW}Build time: ~15-20 minutes${NC}"
echo -e "${YELLOW}Expected size: ~20-25MB per architecture${NC}"
echo ""

# Check NDK
if [ ! -d "$NDK_PATH" ]; then
    echo -e "${RED}Error: NDK not found at $NDK_PATH${NC}"
    echo -e "${YELLOW}Please install NDK 27.0.12077973 from Android Studio${NC}"
    exit 1
fi

# Setup directories
echo -e "${CYAN}Setting up build directories...${NC}"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
mkdir -p "$OUTPUT_BASE/arm64-v8a"
mkdir -p "$OUTPUT_BASE/armeabi-v7a"

cd "$BUILD_DIR"

# Function to build libmp3lame
build_mp3lame() {
    local ABI=$1
    local TOOLCHAIN_PREFIX=$2
    local HOST=$3
    local INSTALL_DIR="$BUILD_DIR/install/$ABI"
    
    echo -e "\n${YELLOW}Building libmp3lame for $ABI...${NC}"
    
    # Setup toolchain
    export TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64"
    export CC="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang"
    export CXX="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export STRIP="$TOOLCHAIN/bin/llvm-strip"
    export NM="$TOOLCHAIN/bin/llvm-nm"
    export AS="$CC"
    export LD="$TOOLCHAIN/bin/ld"
    export SYSROOT="$TOOLCHAIN/sysroot"
    
    # Download LAME if needed
    if [ ! -d "lame-3.100" ]; then
        echo -e "${CYAN}Downloading LAME 3.100...${NC}"
        curl -L "https://sourceforge.net/projects/lame/files/lame/3.100/lame-3.100.tar.gz/download" -o lame-3.100.tar.gz
        tar xzf lame-3.100.tar.gz
    fi
    
    cd lame-3.100
    
    # Clean previous build
    make clean 2>/dev/null || true
    
    # Configure
    ./configure \
        --host="$HOST" \
        --prefix="$INSTALL_DIR" \
        --disable-shared \
        --enable-static \
        --disable-frontend \
        --disable-analyzer-hooks \
        --with-pic \
        CFLAGS="-O3 -fPIC -DANDROID"
    
    # Build
    make -j$JOBS
    make install
    
    cd "$BUILD_DIR"
    
    if [ -f "$INSTALL_DIR/lib/libmp3lame.a" ]; then
        echo -e "${GREEN}✓ libmp3lame built successfully for $ABI${NC}"
    else
        echo -e "${RED}✗ Failed to build libmp3lame for $ABI${NC}"
        exit 1
    fi
}

# Function to build FFmpeg
build_ffmpeg() {
    local ABI=$1
    local TOOLCHAIN_PREFIX=$2
    local ARCH=$3
    local CPU=$4
    local INSTALL_DIR="$BUILD_DIR/install/$ABI"
    
    echo -e "\n${YELLOW}Building FFmpeg for $ABI...${NC}"
    
    # Setup toolchain
    export TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64"
    export CC="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang"
    export CXX="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export STRIP="$TOOLCHAIN/bin/llvm-strip"
    export NM="$TOOLCHAIN/bin/llvm-nm"
    export AS="$CC"
    export LD="$TOOLCHAIN/bin/ld"
    export SYSROOT="$TOOLCHAIN/sysroot"
    
    # Download FFmpeg if needed
    if [ ! -d "ffmpeg-6.0" ]; then
        echo -e "${CYAN}Downloading FFmpeg 6.0...${NC}"
        curl -L "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n6.0.tar.gz" -o ffmpeg-6.0.tar.gz
        tar xzf ffmpeg-6.0.tar.gz
        mv FFmpeg-n6.0 ffmpeg-6.0
    fi
    
    cd ffmpeg-6.0
    
    # Clean previous build
    make clean 2>/dev/null || true
    
    # Setup flags
    PKG_CONFIG_PATH="$INSTALL_DIR/lib/pkgconfig"
    CFLAGS="-O3 -fPIC -DANDROID -I$INSTALL_DIR/include"
    LDFLAGS="-L$INSTALL_DIR/lib -lmp3lame"
    LDFLAGS="$LDFLAGS -Wl,-z,max-page-size=16384"
    LDFLAGS="$LDFLAGS -L$SYSROOT/usr/lib/$HOST/$MIN_API"
    
    # Add compiler runtime library
    if [ "$ABI" = "arm64-v8a" ]; then
        LDFLAGS="$LDFLAGS $TOOLCHAIN/lib/clang/18/lib/linux/libclang_rt.builtins-aarch64-android.a"
    else
        LDFLAGS="$LDFLAGS $TOOLCHAIN/lib/clang/18/lib/linux/libclang_rt.builtins-arm-android.a"
    fi
    
    LDFLAGS="$LDFLAGS -lc -lm -ldl -llog"
    
    # Configure FFmpeg with all features (disable Vulkan for armeabi-v7a)
    if [ "$ABI" = "armeabi-v7a" ]; then
        VULKAN_FLAGS="--disable-vulkan --disable-hwaccel=h264_vulkan --disable-hwaccel=hevc_vulkan"
    else
        VULKAN_FLAGS=""
    fi
    
    ./configure \
        --prefix="$BUILD_DIR/output/$ABI" \
        --target-os=android \
        --arch="$ARCH" \
        --cpu="$CPU" \
        --enable-cross-compile \
        --cc="$CC" \
        --cxx="$CXX" \
        --ar="$AR" \
        --nm="$NM" \
        --ranlib="$RANLIB" \
        --strip="$STRIP" \
        --sysroot="$SYSROOT" \
        --extra-cflags="$CFLAGS" \
        --extra-ldflags="$LDFLAGS" \
        --pkg-config-flags="--static" \
        --enable-static \
        --disable-shared \
        --disable-doc \
        --enable-ffmpeg \
        --disable-ffplay \
        --disable-ffprobe \
        --disable-symver \
        --enable-small \
        --enable-gpl \
        --enable-version3 \
        --enable-nonfree \
        --enable-pic \
        --disable-debug \
        --enable-jni \
        --enable-mediacodec \
        $VULKAN_FLAGS \
        --enable-libmp3lame \
        --enable-encoder=libmp3lame \
        --enable-decoder=mp3 \
        --enable-decoder=mp3float \
        --enable-decoder=mp3adu \
        --enable-decoder=mp3adufloat \
        --enable-decoder=mp3on4 \
        --enable-decoder=mp3on4float \
        --enable-muxer=mp3 \
        --enable-muxer=mp4 \
        --enable-muxer=mov \
        --enable-muxer=avi \
        --enable-muxer=matroska \
        --enable-muxer=webm \
        --enable-muxer=flv \
        --enable-muxer=wav \
        --enable-muxer=adts \
        --enable-demuxer=mp3 \
        --enable-demuxer=mp4 \
        --enable-demuxer=mov \
        --enable-demuxer=avi \
        --enable-demuxer=matroska \
        --enable-demuxer=webm \
        --enable-demuxer=flv \
        --enable-demuxer=wav \
        --enable-demuxer=aac \
        --enable-decoder=aac \
        --enable-decoder=aac_latm \
        --enable-encoder=aac \
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
        --enable-decoder=flac \
        --enable-decoder=vorbis \
        --enable-decoder=opus \
        --enable-decoder=pcm_s16le \
        --enable-decoder=pcm_s16be \
        --enable-decoder=pcm_f32le \
        --enable-encoder=pcm_s16le \
        --enable-protocol=file \
        --enable-protocol=http \
        --enable-protocol=https \
        --enable-protocol=pipe \
        --enable-protocol=concat \
        --enable-filter=aresample \
        --enable-filter=aformat \
        --enable-filter=volume \
        --enable-filter=scale \
        --enable-filter=fps \
        --enable-filter=transpose \
        --enable-filter=overlay \
        --enable-bsf=aac_adtstoasc \
        --enable-bsf=h264_mp4toannexb \
        --enable-bsf=hevc_mp4toannexb
    
    # Build
    make -j$JOBS
    
    # Copy binary to output
    echo -e "${CYAN}Installing FFmpeg for $ABI...${NC}"
    cp ffmpeg "$OUTPUT_BASE/$ABI/libffmpeg.so"
    chmod 755 "$OUTPUT_BASE/$ABI/libffmpeg.so"
    "$STRIP" "$OUTPUT_BASE/$ABI/libffmpeg.so"
    
    cd "$BUILD_DIR"
    
    # Verify
    SIZE=$(ls -lh "$OUTPUT_BASE/$ABI/libffmpeg.so" | awk '{print $5}')
    echo -e "${GREEN}✓ FFmpeg built successfully for $ABI: $SIZE${NC}"
}

# Build for arm64-v8a
echo -e "\n${MAGENTA}========================================${NC}"
echo -e "${MAGENTA}Building for arm64-v8a${NC}"
echo -e "${MAGENTA}========================================${NC}"

build_mp3lame "arm64-v8a" "aarch64-linux-android" "aarch64-linux-android"
build_ffmpeg "arm64-v8a" "aarch64-linux-android" "aarch64" "armv8-a"

# Build for armeabi-v7a
echo -e "\n${MAGENTA}========================================${NC}"
echo -e "${MAGENTA}Building for armeabi-v7a${NC}"
echo -e "${MAGENTA}========================================${NC}"

build_mp3lame "armeabi-v7a" "armv7a-linux-androideabi" "arm-linux-androideabi"
build_ffmpeg "armeabi-v7a" "armv7a-linux-androideabi" "arm" "armv7-a"

# Final summary
echo -e "\n${MAGENTA}================================================${NC}"
echo -e "${MAGENTA}        Build Complete! 🎉${NC}"
echo -e "${MAGENTA}================================================${NC}"
echo ""
echo -e "${GREEN}FFmpeg binaries built with:${NC}"
echo -e "${GREEN}✓ Full GPL license${NC}"
echo -e "${GREEN}✓ MP3 encoding (libmp3lame)${NC}"
echo -e "${GREEN}✓ AAC encoding${NC}"
echo -e "${GREEN}✓ Hardware acceleration (MediaCodec)${NC}"
echo -e "${GREEN}✓ All common codecs and formats${NC}"
echo -e "${GREEN}✓ Vulkan disabled for armeabi-v7a (fixes compilation)${NC}"
echo ""

# Show binary info
echo -e "${CYAN}Binary information:${NC}"
for ABI in arm64-v8a armeabi-v7a; do
    BINARY="$OUTPUT_BASE/$ABI/libffmpeg.so"
    if [ -f "$BINARY" ]; then
        SIZE=$(ls -lh "$BINARY" | awk '{print $5}')
        echo -e "${GREEN}$ABI: $SIZE${NC}"
    fi
done

echo ""
echo -e "${YELLOW}Output location:${NC}"
echo -e "${BLUE}$OUTPUT_BASE${NC}"
echo ""
echo -e "${YELLOW}To use in your Android app:${NC}"
echo -e "1. Rebuild your Android project"
echo -e "2. The binaries are already in the assets folder"
echo -e "3. Test with: -i video.mp4 -c:a libmp3lame -b:a 192k audio.mp3"
echo ""
echo -e "${MAGENTA}Happy coding! 🚀${NC}"