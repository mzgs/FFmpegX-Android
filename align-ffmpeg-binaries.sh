#!/bin/bash

# Script to align FFmpeg binaries for Android 15+ 16KB page size requirement
# This fixes the "LOAD segments not aligned at 16 KB boundaries" warning

set -e

ASSETS_DIR="FFMpegLib/src/main/assets/ffmpeg"

echo "Aligning FFmpeg binaries for 16KB page size..."

# Function to align a binary
align_binary() {
    local input_file="$1"
    local temp_file="${input_file}.aligned"
    
    if [ -f "$input_file" ]; then
        echo "Aligning: $input_file"
        
        # Use objcopy to align sections
        # This requires Android NDK's objcopy tool
        if command -v llvm-objcopy &> /dev/null; then
            llvm-objcopy --set-section-alignment=.text=16384 \
                        --set-section-alignment=.data=16384 \
                        --set-section-alignment=.bss=16384 \
                        "$input_file" "$temp_file"
            mv "$temp_file" "$input_file"
            echo "  ✓ Aligned successfully"
        else
            # Fallback: Use patchelf if available
            if command -v patchelf &> /dev/null; then
                patchelf --page-size 16384 "$input_file"
                echo "  ✓ Aligned with patchelf"
            else
                echo "  ⚠ Warning: Neither llvm-objcopy nor patchelf found. Binary may not be aligned."
                echo "    Install Android NDK or patchelf to fix alignment."
            fi
        fi
    fi
}

# Align all FFmpeg binaries
for abi in "arm64-v8a" "armeabi-v7a"; do
    binary_path="$ASSETS_DIR/$abi/libffmpeg.so"
    align_binary "$binary_path"
done

echo ""
echo "Alignment complete!"
echo ""
echo "Note: If you still see warnings, you can:"
echo "1. Rebuild FFmpeg with proper alignment flags"
echo "2. Or use zipalign on the final APK:"
echo "   zipalign -p -f -v 16384 input.apk output.apk"