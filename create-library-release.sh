#!/bin/bash
set -e

# ================================================
# Create GitHub Release with FFmpeg Libraries
# This script packages and uploads pre-built FFmpeg libraries
# ================================================

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
LIBS_DIR="$SCRIPT_DIR/ffmpegx/src/main/cpp/ffmpeg-libs"
LAME_DIR="$SCRIPT_DIR/ffmpegx/src/main/cpp/lame-libs"
OUTPUT_DIR="/tmp/ffmpeg-release"

echo -e "${CYAN}================================================${NC}"
echo -e "${CYAN}  Creating FFmpeg Libraries Release Package${NC}"
echo -e "${CYAN}================================================${NC}"

# Check if libraries exist
if [ ! -f "$LIBS_DIR/arm64-v8a/lib/libavcodec.a" ]; then
    echo -e "${YELLOW}FFmpeg libraries not found. Building first...${NC}"
    ./build-ffmpeg.sh
fi

# Create output directory
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
cd "$OUTPUT_DIR"

# Copy libraries
echo -e "${CYAN}Packaging libraries...${NC}"
cp -r "$LIBS_DIR" ffmpeg-libs
cp -r "$LAME_DIR" lame-libs

# Create archive
echo -e "${CYAN}Creating archive...${NC}"
tar czf ffmpeg-android-libs.tar.gz ffmpeg-libs lame-libs

# Calculate checksums
echo -e "${CYAN}Calculating checksums...${NC}"
sha256sum ffmpeg-android-libs.tar.gz > ffmpeg-android-libs.tar.gz.sha256

# Get file size
SIZE=$(du -h ffmpeg-android-libs.tar.gz | cut -f1)

echo -e "${GREEN}✓ Release package created successfully${NC}"
echo -e "${CYAN}Archive: $OUTPUT_DIR/ffmpeg-android-libs.tar.gz${NC}"
echo -e "${CYAN}Size: $SIZE${NC}"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo "1. Create a new GitHub Release:"
echo "   - Go to: https://github.com/mzgs/FFmpegX-Android/releases/new"
echo "   - Tag: ffmpeg-libs-v1.0"
echo "   - Title: Pre-built FFmpeg Libraries for Android"
echo "   - Upload: $OUTPUT_DIR/ffmpeg-android-libs.tar.gz"
echo ""
echo "2. Update download-ffmpeg-libs.sh with the release URL"
echo ""
echo "3. Alternative hosting options:"
echo "   - Google Drive (for large files)"
echo "   - AWS S3"
echo "   - Azure Blob Storage"
echo "   - Any CDN service"