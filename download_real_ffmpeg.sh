#!/bin/bash

# Download real FFmpeg binaries for Android
# This script downloads pre-compiled FFmpeg binaries from cropsly

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
ASSETS_DIR="$SCRIPT_DIR/FFMpeg Lib/src/main/assets/ffmpeg"

echo "============================================================"
echo "Downloading Real FFmpeg Binaries for Android"
echo "============================================================"
echo ""
echo "Note: This will download ~50MB of binaries"
echo ""

# Function to download from cropsly GitHub releases
download_from_github() {
    local arch=$1
    local download_name=$2
    
    echo "Downloading for $arch..."
    
    # Use cropsly ffmpeg-android v0.3.4
    local base_url="https://github.com/cropsly/ffmpeg-android/releases/download/v0.3.4"
    
    # Download ffmpeg
    if curl -L -o "$ASSETS_DIR/$arch/ffmpeg" \
        "$base_url/ffmpeg-$download_name" 2>/dev/null || \
       wget -O "$ASSETS_DIR/$arch/ffmpeg" \
        "$base_url/ffmpeg-$download_name" 2>/dev/null; then
        chmod +x "$ASSETS_DIR/$arch/ffmpeg"
        echo "  ✓ Downloaded ffmpeg for $arch"
    else
        echo "  ✗ Failed to download ffmpeg for $arch"
    fi
    
    # Download ffprobe
    if curl -L -o "$ASSETS_DIR/$arch/ffprobe" \
        "$base_url/ffprobe-$download_name" 2>/dev/null || \
       wget -O "$ASSETS_DIR/$arch/ffprobe" \
        "$base_url/ffprobe-$download_name" 2>/dev/null; then
        chmod +x "$ASSETS_DIR/$arch/ffprobe"
        echo "  ✓ Downloaded ffprobe for $arch"
    else
        # Create a simple wrapper if ffprobe not available
        echo '#!/system/bin/sh' > "$ASSETS_DIR/$arch/ffprobe"
        echo 'echo "FFprobe not available"' >> "$ASSETS_DIR/$arch/ffprobe"
        echo 'exit 0' >> "$ASSETS_DIR/$arch/ffprobe"
        chmod +x "$ASSETS_DIR/$arch/ffprobe"
        echo "  ⚠ Created ffprobe placeholder for $arch"
    fi
}

# Download for each architecture from cropsly
# The naming convention at cropsly is ffmpeg-<arch> and ffprobe-<arch>
download_from_github "armeabi-v7a" "armeabi-v7a"
download_from_github "arm64-v8a" "arm64-v8a"
download_from_github "x86" "x86"
download_from_github "x86_64" "x86_64"

echo ""
echo "============================================================"
echo "Verification:"
echo "============================================================"

for arch in armeabi-v7a arm64-v8a x86 x86_64; do
    FFMPEG_FILE="$ASSETS_DIR/$arch/ffmpeg"
    if [ -f "$FFMPEG_FILE" ]; then
        SIZE=$(du -h "$FFMPEG_FILE" 2>/dev/null | cut -f1)
        if file "$FFMPEG_FILE" 2>/dev/null | grep -q "ELF"; then
            echo "✓ $arch: Valid binary ($SIZE)"
        else
            echo "⚠ $arch: File exists but may not be valid ($SIZE)"
        fi
    else
        echo "✗ $arch: Missing"
    fi
done

echo ""
echo "============================================================"
echo "IMPORTANT:"
echo "============================================================"
echo ""
echo "If downloads failed, you need to manually download FFmpeg:"
echo ""
echo "1. Visit: https://github.com/tanersener/mobile-ffmpeg/releases"
echo "2. Download 'mobile-ffmpeg-full-4.4.LTS' for your architecture"
echo "3. Extract the 'ffmpeg' binary to:"
echo "   $ASSETS_DIR/[architecture]/ffmpeg"
echo ""
echo "Or use a Docker build:"
echo "   docker run -v \$(pwd):/output ffmpeg-android-builder"
echo ""
echo "============================================================"