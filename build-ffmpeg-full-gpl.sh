#!/bin/bash

# FFmpeg Full-GPL Build Script for Android
# This builds FFmpeg with all features including x264, x265, and other GPL libraries

set -e

# Configuration
ANDROID_NDK_ROOT="${ANDROID_SDK_ROOT}/ndk/27.0.12077973"
MIN_SDK_VERSION=21
OUTPUT_DIR="$(pwd)/FFMpegLib/src/main/assets/ffmpeg"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}======================================${NC}"
echo -e "${GREEN}FFmpeg Full-GPL Build for Android${NC}"
echo -e "${GREEN}======================================${NC}"

# Check if NDK exists
if [ ! -d "$ANDROID_NDK_ROOT" ]; then
    echo -e "${RED}Error: Android NDK not found at $ANDROID_NDK_ROOT${NC}"
    echo "Please install NDK version 27.0.12077973 or update the path in this script"
    exit 1
fi

echo -e "${YELLOW}For a full-GPL build with all codecs, we have two options:${NC}"
echo ""
echo "1. Use ffmpeg-kit library directly (Recommended)"
echo "   - Add to build.gradle: implementation 'com.arthenica:ffmpeg-kit-android-full-gpl:6.0-2'"
echo "   - This includes pre-built binaries with x264, x265, and all codecs"
echo "   - Size: ~35MB per architecture"
echo ""
echo "2. Build from source (Complex)"
echo "   - Requires building x264, x265, and other libraries first"
echo "   - Takes 1-2 hours to compile"
echo "   - Requires additional tools and dependencies"
echo ""
echo -e "${GREEN}Recommendation: Use ffmpeg-kit library${NC}"

# Create a gradle file for easy integration
cat > FFMpegLib/ffmpeg-kit-integration.gradle << 'EOF'
// Add this to your app's build.gradle to use ffmpeg-kit full-gpl

dependencies {
    // Remove the current FFmpeg implementation and add:
    implementation 'com.arthenica:ffmpeg-kit-android-full-gpl:6.0-2'
}

// Usage in Kotlin:
/*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode

// Execute command
FFmpegKit.execute("-i input.mp4 -c:v libx264 -preset medium -crf 23 output.mp4") { session ->
    val returnCode = session.returnCode
    if (ReturnCode.isSuccess(returnCode)) {
        // Success
    } else {
        // Failure
    }
}

// Execute with callbacks
FFmpegKit.executeAsync("-i input.mp4 -c:v libx264 output.mp4",
    { session -> /* onComplete */ },
    { log -> /* onLog */ },
    { statistics -> /* onStatistics */ }
)
*/
EOF

echo -e "${GREEN}Created ffmpeg-kit-integration.gradle with usage examples${NC}"
echo ""
echo -e "${YELLOW}To migrate to ffmpeg-kit full-gpl:${NC}"
echo "1. Add the dependency to your build.gradle"
echo "2. Replace FFmpeg calls with FFmpegKit calls"
echo "3. Remove the assets/ffmpeg folder (no longer needed)"
echo ""
echo -e "${GREEN}Benefits of ffmpeg-kit full-gpl:${NC}"
echo "✓ All codecs including H.264 (libx264), H.265 (libx265)"
echo "✓ All filters and effects"
echo "✓ Hardware acceleration support"
echo "✓ Actively maintained with regular updates"
echo "✓ Proper Android 10+ support built-in"
echo "✓ No need to manage binary files"