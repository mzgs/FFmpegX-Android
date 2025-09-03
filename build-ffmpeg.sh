#!/bin/bash
set -e

# ================================================
# Complete FFmpeg GPL Build Script for Android
# Includes ALL features and external libraries:
# - OpenSSL (HTTPS/TLS support)
# - LAME (MP3 encoding)
# - x264 (H.264 encoding)
# - FDK-AAC (High-quality AAC)
# - Full FFmpeg with all codecs
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
NDK_PATH="${ANDROID_NDK_HOME:-/Users/mustafa/Library/Android/sdk/ndk/27.0.12077973}"
MIN_API=21
BUILD_DIR="/tmp/ffmpeg-full-build"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
OUTPUT_BASE="$SCRIPT_DIR/ffmpegx/src/main/cpp/ffmpeg-libs"
LAME_OUTPUT="$SCRIPT_DIR/ffmpegx/src/main/cpp/lame-libs"
JOBS=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)

# Library versions
OPENSSL_VERSION="1.1.1w"
LAME_VERSION="3.100"
X264_VERSION="stable"
FDK_AAC_VERSION="2.0.2"
FFMPEG_VERSION="6.0"

echo -e "${MAGENTA}================================================${NC}"
echo -e "${MAGENTA}  Complete FFmpeg GPL Build for Android${NC}"
echo -e "${MAGENTA}  Building ALL external libraries + FFmpeg${NC}"
echo -e "${MAGENTA}================================================${NC}"
echo ""

# Check NDK
if [ ! -d "$NDK_PATH" ]; then
    echo -e "${RED}Error: NDK not found at $NDK_PATH${NC}"
    echo -e "${YELLOW}Please set ANDROID_NDK_HOME or edit this script${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Found NDK at: $NDK_PATH${NC}"

# Setup directories
echo -e "${CYAN}Setting up build directories...${NC}"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
rm -rf "$OUTPUT_BASE"
mkdir -p "$OUTPUT_BASE"
rm -rf "$LAME_OUTPUT"
mkdir -p "$LAME_OUTPUT"

# Build function for OpenSSL
build_openssl() {
    local ABI=$1
    local TOOLCHAIN_PREFIX=$2
    local OPENSSL_ARCH=$3
    
    echo -e "\n${YELLOW}Building OpenSSL for $ABI...${NC}"
    
    local BUILD_PATH="$BUILD_DIR/openssl-$ABI"
    local PREFIX="$BUILD_DIR/openssl-install/$ABI"
    
    rm -rf "$BUILD_PATH"
    cp -r "$BUILD_DIR/openssl-source" "$BUILD_PATH"
    mkdir -p "$PREFIX"
    
    cd "$BUILD_PATH"
    
    # Setup toolchain
    export TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64"
    if [ ! -d "$TOOLCHAIN" ]; then
        TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64"
    fi
    
    export CC="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang"
    export CXX="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export STRIP="$TOOLCHAIN/bin/llvm-strip"
    
    # Configure OpenSSL
    ./Configure $OPENSSL_ARCH \
        --prefix="$PREFIX" \
        --openssldir="$PREFIX" \
        no-shared \
        no-tests \
        no-ui-console \
        no-unit-test \
        -D__ANDROID_API__=$MIN_API \
        || exit 1
    
    # Build
    make -j$JOBS
    make install_sw
    
    echo -e "${GREEN}✓ OpenSSL built for $ABI${NC}"
}

# Build function for LAME
build_lame() {
    local ABI=$1
    local TOOLCHAIN_PREFIX=$2
    local HOST=$3
    
    echo -e "\n${YELLOW}Building LAME for $ABI...${NC}"
    
    local BUILD_PATH="$BUILD_DIR/lame-$ABI"
    local PREFIX="$LAME_OUTPUT/$ABI"
    
    rm -rf "$BUILD_PATH"
    mkdir -p "$BUILD_PATH"
    mkdir -p "$PREFIX"
    
    # Setup toolchain
    export TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64"
    if [ ! -d "$TOOLCHAIN" ]; then
        TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64"
    fi
    
    export CC="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang"
    export CXX="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export STRIP="$TOOLCHAIN/bin/llvm-strip"
    
    cd "$BUILD_PATH"
    
    # Configure LAME
    "$BUILD_DIR/lame-source/configure" \
        --prefix="$PREFIX" \
        --host="$HOST" \
        --disable-shared \
        --enable-static \
        --disable-frontend \
        --disable-decoder \
        --with-pic \
        CFLAGS="-O3 -fPIC -DANDROID" \
        || exit 1
    
    # Build
    make -j$JOBS
    make install
    
    echo -e "${GREEN}✓ LAME built for $ABI${NC}"
}

# Build function for x264
build_x264() {
    local ABI=$1
    local TOOLCHAIN_PREFIX=$2
    local HOST=$3
    local EXTRA_CFLAGS=$4
    
    echo -e "\n${YELLOW}Building x264 for $ABI...${NC}"
    
    local BUILD_PATH="$BUILD_DIR/x264-$ABI"
    local PREFIX="$BUILD_DIR/x264-install/$ABI"
    
    rm -rf "$BUILD_PATH"
    cp -r "$BUILD_DIR/x264-source" "$BUILD_PATH"
    mkdir -p "$PREFIX"
    
    cd "$BUILD_PATH"
    
    # Setup toolchain
    export TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64"
    if [ ! -d "$TOOLCHAIN" ]; then
        TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64"
    fi
    
    export CC="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang"
    export CXX="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export AS="$CC"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export STRIP="$TOOLCHAIN/bin/llvm-strip"
    
    # Configure x264
    ./configure \
        --prefix="$PREFIX" \
        --host="$HOST" \
        --enable-static \
        --enable-pic \
        --disable-cli \
        --disable-asm \
        --cross-prefix="${TOOLCHAIN}/bin/llvm-" \
        --sysroot="$TOOLCHAIN/sysroot" \
        --extra-cflags="-O3 -fPIC $EXTRA_CFLAGS" \
        || exit 1
    
    # Build
    make -j$JOBS
    make install
    
    echo -e "${GREEN}✓ x264 built for $ABI${NC}"
}

# Build function for FDK-AAC
build_fdk_aac() {
    local ABI=$1
    local TOOLCHAIN_PREFIX=$2
    local HOST=$3
    
    echo -e "\n${YELLOW}Building FDK-AAC for $ABI...${NC}"
    
    local BUILD_PATH="$BUILD_DIR/fdk-aac-$ABI"
    local PREFIX="$BUILD_DIR/fdk-aac-install/$ABI"
    
    rm -rf "$BUILD_PATH"
    mkdir -p "$BUILD_PATH"
    mkdir -p "$PREFIX"
    
    # Setup toolchain
    export TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64"
    if [ ! -d "$TOOLCHAIN" ]; then
        TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64"
    fi
    
    export CC="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang"
    export CXX="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export STRIP="$TOOLCHAIN/bin/llvm-strip"
    
    cd "$BUILD_PATH"
    
    # Configure FDK-AAC
    "$BUILD_DIR/fdk-aac-source/configure" \
        --prefix="$PREFIX" \
        --host="$HOST" \
        --disable-shared \
        --enable-static \
        --with-pic \
        CFLAGS="-O3 -fPIC" \
        CXXFLAGS="-O3 -fPIC -std=c++11" \
        || exit 1
    
    # Build
    make -j$JOBS
    make install
    
    echo -e "${GREEN}✓ FDK-AAC built for $ABI${NC}"
}

# Build function for FFmpeg
build_ffmpeg() {
    local ABI=$1
    local TOOLCHAIN_PREFIX=$2
    local ARCH=$3
    local CPU=$4
    local EXTRA_CFLAGS=$5
    local EXTRA_LDFLAGS=$6
    
    echo -e "\n${YELLOW}Building FFmpeg for $ABI...${NC}"
    
    local BUILD_PATH="$BUILD_DIR/ffmpeg-$ABI"
    local PREFIX="$OUTPUT_BASE/$ABI"
    
    rm -rf "$BUILD_PATH"
    mkdir -p "$BUILD_PATH"
    mkdir -p "$PREFIX"
    
    cd "$BUILD_PATH"
    
    # Setup toolchain
    export TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64"
    if [ ! -d "$TOOLCHAIN" ]; then
        TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64"
    fi
    
    export CC="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang"
    export CXX="$TOOLCHAIN/bin/${TOOLCHAIN_PREFIX}${MIN_API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export STRIP="$TOOLCHAIN/bin/llvm-strip"
    export NM="$TOOLCHAIN/bin/llvm-nm"
    export AS="$CC"
    export LD="$TOOLCHAIN/bin/ld"
    export SYSROOT="$TOOLCHAIN/sysroot"
    
    # Add external libraries
    local OPENSSL_DIR="$BUILD_DIR/openssl-install/$ABI"
    local LAME_DIR="$LAME_OUTPUT/$ABI"
    local X264_DIR="$BUILD_DIR/x264-install/$ABI"
    local FDK_AAC_DIR="$BUILD_DIR/fdk-aac-install/$ABI"
    
    EXTRA_CFLAGS="$EXTRA_CFLAGS -I$OPENSSL_DIR/include -I$LAME_DIR/include -I$X264_DIR/include -I$FDK_AAC_DIR/include"
    EXTRA_LDFLAGS="$EXTRA_LDFLAGS -L$OPENSSL_DIR/lib -L$LAME_DIR/lib -L$X264_DIR/lib -L$FDK_AAC_DIR/lib"
    
    # Configure FFmpeg with ALL features
    "$BUILD_DIR/ffmpeg-source/configure" \
        --prefix="$PREFIX" \
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
        --disable-asm \
        --enable-openssl \
        --enable-libmp3lame \
        --enable-libx264 \
        --enable-libfdk-aac \
        --extra-cflags="-O3 -fPIC -DANDROID $EXTRA_CFLAGS -Wl,-z,max-page-size=16384" \
        --extra-ldflags="$EXTRA_LDFLAGS -Wl,-z,max-page-size=16384 -lssl -lcrypto -lmp3lame -lx264 -lfdk-aac" \
        --pkg-config-flags="--static" \
        --enable-static \
        --disable-shared \
        --disable-doc \
        --disable-programs \
        --disable-ffmpeg \
        --disable-ffplay \
        --disable-ffprobe \
        --disable-symver \
        --enable-small \
        --enable-gpl \
        --enable-version3 \
        --enable-nonfree \
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
        --enable-decoder=mp3 \
        --enable-decoder=mp3float \
        --enable-decoder=aac \
        --enable-decoder=aac_latm \
        --enable-decoder=flac \
        --enable-decoder=vorbis \
        --enable-decoder=opus \
        --enable-decoder=pcm_s16le \
        --enable-decoder=pcm_f32le \
        --enable-encoder=libx264 \
        --enable-encoder=aac \
        --enable-encoder=libfdk_aac \
        --enable-encoder=libmp3lame \
        --enable-encoder=pcm_s16le \
        --enable-encoder=mpeg4 \
        --enable-encoder=mpeg2video \
        --enable-encoder=h263 \
        --enable-encoder=h263p \
        --enable-encoder=mjpeg \
        --enable-muxer=mp3 \
        --enable-muxer=mp4 \
        --enable-muxer=mov \
        --enable-muxer=avi \
        --enable-muxer=matroska \
        --enable-muxer=webm \
        --enable-muxer=flv \
        --enable-muxer=wav \
        --enable-muxer=adts \
        --enable-muxer=hls \
        --enable-muxer=dash \
        --enable-demuxer=mp3 \
        --enable-demuxer=aac \
        --enable-demuxer=mov \
        --enable-demuxer=flv \
        --enable-demuxer=matroska \
        --enable-demuxer=wav \
        --enable-demuxer=hls \
        --enable-demuxer=dash \
        --enable-protocol=file \
        --enable-protocol=http \
        --enable-protocol=https \
        --enable-protocol=tls \
        --enable-protocol=pipe \
        --enable-protocol=concat \
        --enable-protocol=crypto \
        --enable-filter=aresample \
        --enable-filter=aformat \
        --enable-filter=volume \
        --enable-filter=scale \
        --enable-filter=fps \
        --enable-filter=transpose \
        --enable-filter=overlay \
        --enable-filter=amix \
        --enable-filter=amerge \
        --enable-filter=pan \
        --enable-filter=equalizer \
        --enable-filter=compand \
        --enable-bsf=aac_adtstoasc \
        --enable-bsf=h264_mp4toannexb \
        --enable-bsf=hevc_mp4toannexb \
        || (cat ffbuild/config.log && exit 1)
    
    # Build
    echo -e "${CYAN}Building FFmpeg libraries...${NC}"
    make -j$JOBS
    make install
    
    # Copy headers to common location (only once)
    if [ ! -d "$OUTPUT_BASE/include" ]; then
        cp -r "$PREFIX/include" "$OUTPUT_BASE/"
    fi
    
    # List the generated libraries
    echo -e "${GREEN}✓ FFmpeg built for $ABI:${NC}"
    ls -la "$PREFIX/lib/"*.a | head -10
    
    cd "$SCRIPT_DIR"
}

# Main build process
cd "$BUILD_DIR"

# Download OpenSSL source
echo -e "\n${CYAN}Downloading OpenSSL ${OPENSSL_VERSION} source...${NC}"
if [ ! -f "openssl.tar.gz" ]; then
    curl -L "https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz" -o openssl.tar.gz
fi
tar xzf openssl.tar.gz
mv "openssl-${OPENSSL_VERSION}" "openssl-source"

# Download LAME source
echo -e "\n${CYAN}Downloading LAME ${LAME_VERSION} source...${NC}"
if [ ! -f "lame.tar.gz" ]; then
    curl -L "https://sourceforge.net/projects/lame/files/lame/${LAME_VERSION}/lame-${LAME_VERSION}.tar.gz/download" -o lame.tar.gz
fi
tar xzf lame.tar.gz
mv "lame-${LAME_VERSION}" "lame-source"

# Download x264 source
echo -e "\n${CYAN}Downloading x264 source...${NC}"
if [ ! -d "x264-source" ]; then
    git clone --depth 1 https://code.videolan.org/videolan/x264.git x264-source
fi

# Download FDK-AAC source
echo -e "\n${CYAN}Downloading FDK-AAC ${FDK_AAC_VERSION} source...${NC}"
if [ ! -f "fdk-aac.tar.gz" ]; then
    curl -L "https://github.com/mstorsjo/fdk-aac/archive/v${FDK_AAC_VERSION}.tar.gz" -o fdk-aac.tar.gz
fi
tar xzf fdk-aac.tar.gz
mv "fdk-aac-${FDK_AAC_VERSION}" "fdk-aac-source"
cd "fdk-aac-source"
./autogen.sh
cd "$BUILD_DIR"

# Download FFmpeg source
echo -e "\n${CYAN}Downloading FFmpeg ${FFMPEG_VERSION} source...${NC}"
if [ ! -f "ffmpeg.tar.gz" ]; then
    curl -L "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n${FFMPEG_VERSION}.tar.gz" -o ffmpeg.tar.gz
fi
tar xzf ffmpeg.tar.gz
mv "FFmpeg-n${FFMPEG_VERSION}" "ffmpeg-source"

# Build all libraries for ARM architectures
echo -e "\n${MAGENTA}================================================${NC}"
echo -e "${MAGENTA}  Building ALL Libraries for ARM architectures${NC}"
echo -e "${MAGENTA}================================================${NC}"

# Build OpenSSL
build_openssl "arm64-v8a" "aarch64-linux-android" "android-arm64"
build_openssl "armeabi-v7a" "armv7a-linux-androideabi" "android-arm"

# Build LAME
build_lame "arm64-v8a" "aarch64-linux-android" "aarch64-linux"
build_lame "armeabi-v7a" "armv7a-linux-androideabi" "arm-linux"

# Build x264
build_x264 "arm64-v8a" "aarch64-linux-android" "aarch64-linux" ""
build_x264 "armeabi-v7a" "armv7a-linux-androideabi" "arm-linux" "-mfpu=vfpv3-d16 -mfloat-abi=softfp"

# Build FDK-AAC
build_fdk_aac "arm64-v8a" "aarch64-linux-android" "aarch64-linux-android"
build_fdk_aac "armeabi-v7a" "armv7a-linux-androideabi" "arm-linux-androideabi"

# Build FFmpeg with all features
echo -e "\n${MAGENTA}================================================${NC}"
echo -e "${MAGENTA}  Building FFmpeg with ALL features${NC}"
echo -e "${MAGENTA}================================================${NC}"

# arm64-v8a
build_ffmpeg \
    "arm64-v8a" \
    "aarch64-linux-android" \
    "aarch64" \
    "armv8-a" \
    "-march=armv8-a" \
    ""

# armeabi-v7a
build_ffmpeg \
    "armeabi-v7a" \
    "armv7a-linux-androideabi" \
    "arm" \
    "armv7-a" \
    "-march=armv7-a -mfpu=vfpv3-d16 -mfloat-abi=softfp" \
    "-Wl,--fix-cortex-a8"

# Create CMake configuration
echo -e "\n${CYAN}Creating CMake configuration...${NC}"

cat > "$OUTPUT_BASE/FFmpegConfig.cmake" << 'EOF'
# FFmpeg Complete GPL Build Configuration for Android JNI

set(FFMPEG_ROOT_DIR ${CMAKE_CURRENT_LIST_DIR})
set(FFMPEG_INCLUDE_DIR ${FFMPEG_ROOT_DIR}/include)

# Function to add FFmpeg libraries for current ABI
function(add_ffmpeg_static_libraries target)
    # Determine the ABI-specific library directory
    set(FFMPEG_LIB_DIR ${FFMPEG_ROOT_DIR}/${ANDROID_ABI}/lib)
    
    if(NOT EXISTS ${FFMPEG_LIB_DIR})
        message(WARNING "FFmpeg libraries not found for ABI: ${ANDROID_ABI}")
        return()
    endif()
    
    # Include directories
    target_include_directories(${target} PRIVATE ${FFMPEG_INCLUDE_DIR})
    
    # Link FFmpeg static libraries in the correct order
    target_link_libraries(${target}
        ${FFMPEG_LIB_DIR}/libavformat.a
        ${FFMPEG_LIB_DIR}/libavcodec.a
        ${FFMPEG_LIB_DIR}/libavfilter.a
        ${FFMPEG_LIB_DIR}/libswscale.a
        ${FFMPEG_LIB_DIR}/libswresample.a
        ${FFMPEG_LIB_DIR}/libavutil.a
        ${FFMPEG_LIB_DIR}/libavdevice.a
        ${FFMPEG_LIB_DIR}/libpostproc.a
        # System libraries
        z
        m
        log
        android
        mediandk
        OpenSLES
    )
    
    # Add external libraries
    set(LAME_LIB "${CMAKE_CURRENT_LIST_DIR}/../lame-libs/${ANDROID_ABI}/lib/libmp3lame.a")
    if(EXISTS ${LAME_LIB})
        target_link_libraries(${target} ${LAME_LIB})
        message(STATUS "Added LAME MP3 encoder for ${ANDROID_ABI}")
    endif()
    
    message(STATUS "FFmpeg complete GPL libraries configured for ${target} (${ANDROID_ABI})")
endfunction()
EOF

# Final summary
echo -e "\n${MAGENTA}================================================${NC}"
echo -e "${GREEN}  ✓ Complete GPL Build Success!${NC}"
echo -e "${MAGENTA}================================================${NC}"
echo ""
echo -e "${GREEN}Built Libraries:${NC}"
echo -e "  • OpenSSL ${OPENSSL_VERSION} (HTTPS/TLS support)"
echo -e "  • LAME ${LAME_VERSION} (MP3 encoder)"
echo -e "  • x264 (H.264 encoder)"
echo -e "  • FDK-AAC ${FDK_AAC_VERSION} (High-quality AAC)"
echo -e "  • FFmpeg ${FFMPEG_VERSION} (Full GPL build)"
echo ""
echo -e "${GREEN}Features:${NC}"
echo -e "  • HTTPS/TLS support via OpenSSL"
echo -e "  • High-quality H.264 encoding with x264"
echo -e "  • High-quality AAC encoding with FDK-AAC"
echo -e "  • MP3 encoding/decoding with LAME"
echo -e "  • Hardware acceleration with MediaCodec"
echo -e "  • All major formats and codecs"
echo -e "  • Advanced filters and effects"
echo ""
echo -e "${CYAN}Build complete! Libraries are in:${NC}"
echo -e "  FFmpeg: $OUTPUT_BASE"
echo -e "  LAME: $LAME_OUTPUT"
echo ""
echo -e "${YELLOW}To build the Android app:${NC}"
echo -e "  ./gradlew assembleDebug"
echo ""