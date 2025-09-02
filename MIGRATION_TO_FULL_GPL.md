# Migration to FFmpeg Full-GPL

You have two options to get FFmpeg with full GPL support (including x264, x265, and all codecs):

## Option 1: Use ffmpeg-kit Library (Recommended)

### Step 1: Update build.gradle.kts

```kotlin
// In FFMpegLib/build.gradle.kts
dependencies {
    // Add this:
    api("com.arthenica:ffmpeg-kit-android-full-gpl:6.0-2")
    
    // Your existing dependencies...
}
```

### Step 2: Create FFmpegKit Wrapper

Create a new file `FFMpegLib/src/main/java/com/mzgs/ffmpeglib/FFmpegKitWrapper.kt`:

```kotlin
package com.mzgs.ffmpeglib

import com.arthenica.ffmpegkit.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class FFmpegKitWrapper {
    
    suspend fun execute(command: String): Boolean = suspendCancellableCoroutine { cont ->
        FFmpegKit.executeAsync(command) { session ->
            val returnCode = session.returnCode
            if (ReturnCode.isSuccess(returnCode)) {
                cont.resume(true)
            } else {
                cont.resume(false)
            }
        }
    }
    
    fun executeWithCallback(
        command: String,
        onComplete: (Boolean) -> Unit,
        onProgress: (Statistics) -> Unit = {}
    ) {
        FFmpegKit.executeAsync(command,
            { session ->
                onComplete(ReturnCode.isSuccess(session.returnCode))
            },
            { /* log */ },
            { statistics ->
                onProgress(statistics)
            }
        )
    }
}
```

### Step 3: Update FFmpegOperations for Full Codec Support

```kotlin
// Now you can use all codecs including:
suspend fun compressVideoH264(
    inputPath: String,
    outputPath: String,
    quality: VideoQuality = VideoQuality.MEDIUM,
    callback: FFmpegHelper.FFmpegCallback? = null
): Boolean {
    val command = FFmpegCommandBuilder()
        .input(inputPath)
        .overwriteOutput()
        .videoCodec("libx264")  // Now works!
        .preset(quality.preset)  // Now works!
        .crf(quality.crf)       // Now works!
        .audioCodec("aac")
        .output(outputPath)
        .build()
    
    return ffmpegHelper.execute(command, callback)
}

suspend fun compressVideoH265(
    inputPath: String,
    outputPath: String,
    quality: VideoQuality = VideoQuality.MEDIUM,
    callback: FFmpegHelper.FFmpegCallback? = null
): Boolean {
    val command = FFmpegCommandBuilder()
        .input(inputPath)
        .overwriteOutput()
        .videoCodec("libx265")  // HEVC/H.265 support!
        .preset(quality.preset)
        .crf(quality.crf)
        .audioCodec("aac")
        .output(outputPath)
        .build()
    
    return ffmpegHelper.execute(command, callback)
}
```

## Option 2: Download Pre-built Full-GPL Binaries

### Download from Mobile FFmpeg (Legacy but works)

```bash
# Download Mobile FFmpeg full-gpl binaries
cd /tmp

# For arm64-v8a
wget https://github.com/tanersener/mobile-ffmpeg/releases/download/v4.4.LTS/mobile-ffmpeg-full-gpl-4.4.LTS-android-arm64-v8a.zip

# For armeabi-v7a  
wget https://github.com/tanersener/mobile-ffmpeg/releases/download/v4.4.LTS/mobile-ffmpeg-full-gpl-4.4.LTS-android-arm-v7a.zip

# Extract and copy
unzip mobile-ffmpeg-full-gpl-4.4.LTS-android-arm64-v8a.zip
cp mobile-ffmpeg-full-gpl-4.4.LTS-android-arm64-v8a/ffmpeg /path/to/assets/ffmpeg/arm64-v8a/libffmpeg.so
```

## Benefits of Full-GPL Build

### Codecs Available:
- **libx264** - H.264 encoding (best compatibility)
- **libx265** - H.265/HEVC encoding (better compression)
- **libvpx** - VP8/VP9 encoding (WebM)
- **libopus** - Opus audio (best quality)
- **libfdk-aac** - High quality AAC
- **libmp3lame** - MP3 encoding
- **libvorbis** - Ogg Vorbis

### Filters Available:
- All video filters (300+)
- All audio filters (100+)
- Complex filter graphs
- Hardware acceleration

### Formats:
- All input formats (300+)
- All output formats (200+)

## Size Comparison

| Build Type | Size per Architecture | Features |
|------------|----------------------|----------|
| Current (minimal) | 13-18 MB | Basic codecs only |
| Full-GPL | 35-40 MB | All codecs & features |
| ffmpeg-kit min | 3-5 MB | Very basic |
| ffmpeg-kit full-gpl | 35-40 MB | Everything |

## Testing Full-GPL Features

After migration, test these commands:

```bash
# H.264 with presets (now works!)
-i {input} -c:v libx264 -preset slow -crf 22 {output}

# H.265/HEVC encoding
-i {input} -c:v libx265 -preset medium -crf 28 {output}

# VP9 for WebM
-i {input} -c:v libvpx-vp9 -crf 30 -b:v 0 {output}.webm

# High quality audio
-i {input} -c:a libfdk_aac -b:a 192k {output}

# Complex filtergraph
-i {input} -filter_complex "[0:v]scale=1280:720,fps=30[v]" -map "[v]" {output}
```

## Recommendation

Use **Option 1 (ffmpeg-kit)** because:
- Maintained and updated regularly
- Easier integration
- Proper Android 10+ support
- No need to manage binary files
- Professional quality builds