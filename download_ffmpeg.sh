#!/bin/bash

# FFmpeg Binary Download Script for Android
# This script downloads pre-compiled FFmpeg binaries for Android

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
ASSETS_DIR="$SCRIPT_DIR/FFMpeg Lib/src/main/assets/ffmpeg"

echo "============================================================"
echo "FFmpeg Android Binary Downloader"
echo "============================================================"

# Create directories
mkdir -p "$ASSETS_DIR"/{arm64-v8a,armeabi-v7a,x86,x86_64}

# Function to download and extract mobile-ffmpeg
download_mobile_ffmpeg() {
    local arch=$1
    local arch_name=$2
    
    echo "Downloading mobile-ffmpeg for $arch..."
    
    local version="4.4.LTS"
    local url="https://github.com/tanersener/mobile-ffmpeg/releases/download/v${version}/mobile-ffmpeg-full-${version}-android-${arch_name}.zip"
    local temp_file="/tmp/mobile-ffmpeg-${arch}.zip"
    
    # Download
    if curl -L -o "$temp_file" "$url" 2>/dev/null || wget -O "$temp_file" "$url" 2>/dev/null; then
        echo "Extracting $arch..."
        
        # Extract ffmpeg and ffprobe
        unzip -j "$temp_file" "*/ffmpeg" -d "$ASSETS_DIR/$arch/" 2>/dev/null
        unzip -j "$temp_file" "*/ffprobe" -d "$ASSETS_DIR/$arch/" 2>/dev/null
        
        # Make executable
        chmod +x "$ASSETS_DIR/$arch/ffmpeg" 2>/dev/null
        chmod +x "$ASSETS_DIR/$arch/ffprobe" 2>/dev/null
        
        rm "$temp_file"
        echo "✓ Downloaded $arch"
        return 0
    else
        echo "✗ Failed to download $arch"
        return 1
    fi
}

# Try to download from mobile-ffmpeg
echo ""
echo "Attempting to download from mobile-ffmpeg (4.4.LTS)..."
echo "------------------------------------------------------------"

download_mobile_ffmpeg "arm64-v8a" "arm64-v8a"
download_mobile_ffmpeg "armeabi-v7a" "arm-v7a"
download_mobile_ffmpeg "x86" "x86"
download_mobile_ffmpeg "x86_64" "x86_64"

# Alternative: Create a simple download helper
echo ""
echo "============================================================"
echo "Alternative Manual Download Instructions:"
echo "============================================================"
echo ""
echo "If automatic download failed, you can manually download from:"
echo ""
echo "1. Mobile FFmpeg (Recommended):"
echo "   https://github.com/tanersener/mobile-ffmpeg/releases"
echo "   Download the 'mobile-ffmpeg-full-4.4.LTS' packages"
echo ""
echo "2. FFmpeg Kit (Alternative):"
echo "   https://github.com/arthenica/ffmpeg-kit/releases"
echo "   Download the Android AAR files (before June 2025)"
echo ""
echo "3. Build from source:"
echo "   git clone https://github.com/FFmpeg/FFmpeg.git"
echo "   Use Android NDK to cross-compile"
echo ""
echo "Place the binaries in:"
echo "  $ASSETS_DIR/[architecture]/"
echo ""
echo "Required files per architecture:"
echo "  - ffmpeg"
echo "  - ffprobe"
echo "============================================================"

# Verify what we have
echo ""
echo "Current status:"
echo "------------------------------------------------------------"

for arch in arm64-v8a armeabi-v7a x86 x86_64; do
    echo -n "$arch: "
    if [ -f "$ASSETS_DIR/$arch/ffmpeg" ] && [ -f "$ASSETS_DIR/$arch/ffprobe" ]; then
        size_ffmpeg=$(du -h "$ASSETS_DIR/$arch/ffmpeg" 2>/dev/null | cut -f1)
        size_ffprobe=$(du -h "$ASSETS_DIR/$arch/ffprobe" 2>/dev/null | cut -f1)
        echo "✓ (ffmpeg: $size_ffmpeg, ffprobe: $size_ffprobe)"
    else
        echo "✗ Missing"
        
        # Create placeholders
        echo "#!/system/bin/sh" > "$ASSETS_DIR/$arch/ffmpeg"
        echo "echo 'FFmpeg placeholder - please install actual binary'" >> "$ASSETS_DIR/$arch/ffmpeg"
        echo "exit 1" >> "$ASSETS_DIR/$arch/ffmpeg"
        chmod +x "$ASSETS_DIR/$arch/ffmpeg"
        
        cp "$ASSETS_DIR/$arch/ffmpeg" "$ASSETS_DIR/$arch/ffprobe"
    fi
done

echo "============================================================"
echo ""
echo "Done! Run './gradlew verifyFFmpegBinaries' to verify installation."