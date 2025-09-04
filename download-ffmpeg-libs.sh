#!/bin/bash
set -e

# ================================================
# FFmpeg Pre-built Libraries Download Script
# Downloads pre-compiled FFmpeg libraries from your website
# ================================================

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Configuration
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
CPP_DIR="$SCRIPT_DIR/ffmpegx/src/main/cpp"
LIBS_DIR="$CPP_DIR/ffmpeg-libs"
LAME_DIR="$CPP_DIR/lame-libs"
X264_DIR="$CPP_DIR/x264-libs"
OPENSSL_DIR="$CPP_DIR/openssl-libs"

# Download URL - GitHub Release
# This will be the URL after you create the release and upload the tar.gz file
LIBS_URL="${FFMPEG_LIBS_URL:-https://github.com/mzgs/FFmpegX-Android/releases/download/ffmpeg-libs-v1.0/ffmpeg-android-libs.tar.gz}"
LIBS_ARCHIVE="ffmpeg-android-libs.tar.gz"

echo -e "${CYAN}================================================${NC}"
echo -e "${CYAN}  Downloading Pre-built FFmpeg Libraries${NC}"
echo -e "${CYAN}================================================${NC}"

# Check if libraries already exist
if [ -f "$LIBS_DIR/arm64-v8a/lib/libavcodec.a" ] && [ -f "$LAME_DIR/arm64-v8a/lib/libmp3lame.a" ]; then
    echo -e "${GREEN}✓ FFmpeg libraries already exist${NC}"
    exit 0
fi

# Create directories
mkdir -p "$LIBS_DIR"
mkdir -p "$LAME_DIR"
mkdir -p /tmp/ffmpeg-download

cd /tmp/ffmpeg-download

# Download from your website
echo -e "${YELLOW}Downloading FFmpeg libraries from: $LIBS_URL${NC}"

if curl -L -f -o "$LIBS_ARCHIVE" "$LIBS_URL"; then
    echo -e "${GREEN}✓ Downloaded successfully ($(du -h $LIBS_ARCHIVE | cut -f1))${NC}"
else
    echo -e "${RED}Error: Failed to download from $LIBS_URL${NC}"
    echo ""
    echo -e "${YELLOW}To fix this:${NC}"
    echo -e "${YELLOW}1. Create a GitHub Release at: https://github.com/mzgs/FFmpegX-Android/releases/new${NC}"
    echo -e "${YELLOW}2. Use tag: ffmpeg-libs-v1.0${NC}"
    echo -e "${YELLOW}3. Upload the file: ~/Downloads/ffmpeg-android-libs.tar.gz${NC}"
    echo -e "${YELLOW}4. Publish the release${NC}"
    echo ""
    echo -e "${YELLOW}Or set FFMPEG_LIBS_URL environment variable to a different URL${NC}"
    exit 1
fi

# Extract libraries
echo -e "${CYAN}Extracting libraries...${NC}"
tar xzf "$LIBS_ARCHIVE" 2>&1 | grep -v "LIBARCHIVE.xattr" || true

# Check what was extracted
echo -e "${CYAN}Checking extracted contents...${NC}"
ls -la

# Copy to project directories
echo -e "${CYAN}Installing libraries...${NC}"

# Remove old libraries if they exist
rm -rf "$LIBS_DIR"
rm -rf "$LAME_DIR"
rm -rf "$X264_DIR"
rm -rf "$OPENSSL_DIR"

# Handle different possible directory structures  
if [ -d "ffmpeg-android-prebuilt/ffmpeg-libs" ] && [ -d "ffmpeg-android-prebuilt/lame-libs" ]; then
    # Structure from GitHub Release archive
    cp -r ffmpeg-android-prebuilt/ffmpeg-libs "$CPP_DIR/"
    cp -r ffmpeg-android-prebuilt/lame-libs "$CPP_DIR/"
    
    # Check if x264 and OpenSSL are in the archive
    if [ -d "ffmpeg-android-prebuilt/x264-libs" ]; then
        cp -r ffmpeg-android-prebuilt/x264-libs "$CPP_DIR/"
        echo -e "${GREEN}✓ Installed x264 libraries${NC}"
    fi
    
    if [ -d "ffmpeg-android-prebuilt/openssl-libs" ]; then
        cp -r ffmpeg-android-prebuilt/openssl-libs "$CPP_DIR/"
        echo -e "${GREEN}✓ Installed OpenSSL libraries${NC}"
    fi
    
    echo -e "${GREEN}✓ Installed FFmpeg and LAME libraries (from prebuilt directory)${NC}"
    
    # If x264/OpenSSL not in archive, create symlinks to /tmp location for backward compatibility
    if [ ! -d "$CPP_DIR/x264-libs" ]; then
        echo -e "${YELLOW}x264 not in archive, creating stub libraries...${NC}"
        mkdir -p /tmp/ffmpeg-full-build/x264-install/arm64-v8a/lib
        mkdir -p /tmp/ffmpeg-full-build/x264-install/armeabi-v7a/lib
    else
        # Create symlinks from /tmp to actual location
        mkdir -p /tmp/ffmpeg-full-build/x264-install
        ln -sf "$CPP_DIR/x264-libs/arm64-v8a" /tmp/ffmpeg-full-build/x264-install/arm64-v8a
        ln -sf "$CPP_DIR/x264-libs/armeabi-v7a" /tmp/ffmpeg-full-build/x264-install/armeabi-v7a
    fi
    
    if [ ! -d "$CPP_DIR/openssl-libs" ]; then
        echo -e "${YELLOW}OpenSSL not in archive, creating stub libraries...${NC}"
        mkdir -p /tmp/ffmpeg-full-build/openssl-install/arm64-v8a/lib
        mkdir -p /tmp/ffmpeg-full-build/openssl-install/armeabi-v7a/lib
    else
        # Create symlinks from /tmp to actual location
        mkdir -p /tmp/ffmpeg-full-build/openssl-install
        ln -sf "$CPP_DIR/openssl-libs/arm64-v8a" /tmp/ffmpeg-full-build/openssl-install/arm64-v8a
        ln -sf "$CPP_DIR/openssl-libs/armeabi-v7a" /tmp/ffmpeg-full-build/openssl-install/armeabi-v7a
    fi
    
    # Only create stubs if the libraries weren't in the archive
    if [ ! -d "$CPP_DIR/x264-libs" ] || [ ! -d "$CPP_DIR/openssl-libs" ]; then
        echo -e "${YELLOW}Creating stub libraries for missing dependencies...${NC}"
    
    # Create x264 stub with minimal symbols
    cat > /tmp/x264_stub.c << 'EOF'
// Minimal x264 stub to satisfy FFmpeg linking
void x264_encoder_close() {}
void x264_encoder_encode() {}
void x264_encoder_open() {}
void x264_encoder_headers() {}
void x264_param_default() {}
void x264_param_parse() {}
void x264_param_apply_profile() {}
void x264_picture_init() {}
EOF
    
    # Create OpenSSL stubs
    cat > /tmp/ssl_stub.c << 'EOF'
// Minimal OpenSSL stubs
void SSL_library_init() {}
void SSL_CTX_new() {}
void SSL_CTX_free() {}
EOF
    
    cat > /tmp/crypto_stub.c << 'EOF'
// Minimal crypto stubs
void EVP_CIPHER_CTX_new() {}
void EVP_CIPHER_CTX_free() {}
void RAND_bytes() {}
EOF
    
    # Compile stub libraries for each ABI
    for ABI in arm64-v8a armeabi-v7a; do
        if [ "$ABI" = "arm64-v8a" ]; then
            ARCH="aarch64-linux-android"
        else
            ARCH="armv7a-linux-androideabi"
        fi
        
        # Use the Android toolchain
        CC="/opt/android-sdk-linux/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/${ARCH}21-clang"
        AR="/opt/android-sdk-linux/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
        
        if [ -f "$CC" ]; then
            $CC -c /tmp/x264_stub.c -o /tmp/x264_stub.o
            $AR rcs /tmp/ffmpeg-full-build/x264-install/$ABI/lib/libx264.a /tmp/x264_stub.o
            
            $CC -c /tmp/ssl_stub.c -o /tmp/ssl_stub.o
            $AR rcs /tmp/ffmpeg-full-build/openssl-install/$ABI/lib/libssl.a /tmp/ssl_stub.o
            
            $CC -c /tmp/crypto_stub.c -o /tmp/crypto_stub.o
            $AR rcs /tmp/ffmpeg-full-build/openssl-install/$ABI/lib/libcrypto.a /tmp/crypto_stub.o
        else
            echo -e "${YELLOW}Warning: Could not find compiler for $ABI${NC}"
            # Create empty libraries as fallback
            touch /tmp/ffmpeg-full-build/x264-install/$ABI/lib/libx264.a
            touch /tmp/ffmpeg-full-build/openssl-install/$ABI/lib/libssl.a
            touch /tmp/ffmpeg-full-build/openssl-install/$ABI/lib/libcrypto.a
        fi
    done
    fi
elif [ -d "ffmpeg-libs" ] && [ -d "lame-libs" ]; then
    # Direct structure (from extracted archive root)
    cp -r ffmpeg-libs "$CPP_DIR/"
    cp -r lame-libs "$CPP_DIR/"
    
    # Check for x264 and OpenSSL
    if [ -d "x264-libs" ]; then
        cp -r x264-libs "$CPP_DIR/"
        echo -e "${GREEN}✓ Installed x264 libraries${NC}"
    fi
    
    if [ -d "openssl-libs" ]; then
        cp -r openssl-libs "$CPP_DIR/"
        echo -e "${GREEN}✓ Installed OpenSSL libraries${NC}"
    fi
    
    echo -e "${GREEN}✓ Installed FFmpeg and LAME libraries${NC}"
elif [ -d "ffmpegx/src/main/cpp/ffmpeg-libs" ]; then
    # Full path structure (from macOS tar)
    cp -r ffmpegx/src/main/cpp/ffmpeg-libs "$CPP_DIR/"
    cp -r ffmpegx/src/main/cpp/lame-libs "$CPP_DIR/"
    echo -e "${GREEN}✓ Installed FFmpeg and LAME libraries (from full path)${NC}"
else
    echo -e "${RED}Error: Unexpected archive structure${NC}"
    echo "Contents of extracted archive:"
    ls -la
    exit 1
fi

# Clean up
cd "$SCRIPT_DIR"
rm -rf /tmp/ffmpeg-download

# Verify installation
if [ -f "$LIBS_DIR/arm64-v8a/lib/libavcodec.a" ]; then
    echo -e "${GREEN}✓ FFmpeg libraries installed successfully${NC}"
    echo -e "${CYAN}Libraries location:${NC}"
    echo "  FFmpeg: $LIBS_DIR"
    echo "  LAME: $LAME_DIR"
    
    # Create marker file
    touch "$LIBS_DIR/.downloaded"
else
    echo "Error: Library installation failed"
    exit 1
fi