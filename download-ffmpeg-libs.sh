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

# Handle different possible directory structures
if [ -d "ffmpeg-android-prebuilt/ffmpeg-libs" ] && [ -d "ffmpeg-android-prebuilt/lame-libs" ]; then
    # Structure from GitHub Release archive
    cp -r ffmpeg-android-prebuilt/ffmpeg-libs "$CPP_DIR/"
    cp -r ffmpeg-android-prebuilt/lame-libs "$CPP_DIR/"
    echo -e "${GREEN}✓ Installed FFmpeg and LAME libraries (from prebuilt directory)${NC}"
elif [ -d "ffmpeg-libs" ] && [ -d "lame-libs" ]; then
    # Direct structure
    cp -r ffmpeg-libs "$CPP_DIR/"
    cp -r lame-libs "$CPP_DIR/"
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