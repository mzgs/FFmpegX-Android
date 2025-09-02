#!/bin/bash
# Test script to verify MP3 encoding capability in FFmpeg binary

echo "========================================="
echo "FFmpeg MP3 Encoding Test"
echo "========================================="
echo ""
echo "This script will help you test if MP3 encoding works in your Android app"
echo ""
echo "After rebuilding and installing your app, run this command on the device:"
echo ""
echo "Using ADB shell:"
echo "----------------"
echo "adb shell"
echo "cd /data/data/com.mzgs.ffmpegx/files"
echo "./libffmpeg.so -encoders | grep mp3"
echo ""
echo "If MP3 encoding is available, you should see:"
echo " A..... libmp3lame           MP3 (MPEG audio layer 3) (codec mp3)"
echo ""
echo "To test actual MP3 encoding, run:"
echo "./libffmpeg.so -f lavfi -i 'sine=frequency=1000:duration=3' -c:a libmp3lame test.mp3"
echo ""
echo "If successful, a test.mp3 file will be created"
echo ""
echo "========================================="
echo "Binary Information:"
echo "========================================="
echo ""
BINARY="/Users/mustafa/AndroidStudioProjects/FfmpegLib/FFMpegLib/src/main/assets/ffmpeg/arm64-v8a/libffmpeg.so"
if [ -f "$BINARY" ]; then
    SIZE=$(ls -lh "$BINARY" | awk '{print $5}')
    DATE=$(ls -l "$BINARY" | awk '{print $6, $7, $8}')
    echo "arm64-v8a binary:"
    echo "  Size: $SIZE"
    echo "  Date: $DATE"
    echo "  Path: $BINARY"
else
    echo "arm64-v8a binary not found!"
fi
echo ""
BINARY="/Users/mustafa/AndroidStudioProjects/FfmpegLib/FFMpegLib/src/main/assets/ffmpeg/armeabi-v7a/libffmpeg.so"
if [ -f "$BINARY" ]; then
    SIZE=$(ls -lh "$BINARY" | awk '{print $5}')
    DATE=$(ls -l "$BINARY" | awk '{print $6, $7, $8}')
    echo "armeabi-v7a binary:"
    echo "  Size: $SIZE"
    echo "  Date: $DATE"
    echo "  Path: $BINARY"
else
    echo "armeabi-v7a binary not found!"
fi
echo ""
echo "========================================="
echo "Next Steps:"
echo "========================================="
echo "1. Rebuild your Android app"
echo "2. Install it on your device"
echo "3. Run the test commands above via ADB"
echo "4. Try extracting audio from a video to MP3"
echo ""
echo "Example audio extraction command:"
echo "-i video.mp4 -c:a libmp3lame -b:a 192k -ar 44100 audio.mp3"