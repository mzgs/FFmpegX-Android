# FFmpegX-Android

A powerful, standalone FFmpeg library for Android that works on all Android versions including Android 10+ without root access. This library provides a complete FFmpeg 6.0 implementation with extensive codec support, hardware acceleration, and a simple Kotlin API.

[![](https://jitpack.io/v/mzgs/FFmpegX-Android.svg)](https://jitpack.io/#mzgs/FFmpegX-Android)

## ✨ Features

✅ **Android 10+ Support** - Works on Android 10, 11, 12, 13, 14, 15+ using JNI wrapper approach  
✅ **No External Dependencies** - Self-contained library with built-in FFmpeg binaries  
✅ **Extensive Codec Support** - H.264, H.265, VP8/VP9, MPEG4, AAC, MP3, LAME, Opus, and more  
✅ **Hardware Acceleration** - MediaCodec support for faster encoding/decoding  
✅ **300+ Filters** - All major video and audio filters included  
✅ **Network Protocols** - HTTP, HTTPS, RTMP, HLS streaming support  
✅ **Kotlin Coroutines** - Modern async API with suspend functions  
✅ **Progress Tracking** - Real-time progress callbacks for long operations  
✅ **Session Management** - Cancel and manage multiple FFmpeg operations  
✅ **Automatic Library Download** - FFmpeg libraries are downloaded automatically from GitHub Release  

## 🚀 Quick Start

### Installation

#### Option 1: JitPack (Recommended)

1. Add JitPack repository to your root `build.gradle.kts` or `settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

2. Add dependency to your app's `build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.github.mzgs:FFmpegX-Android:v2.2.0")
}
```

#### Option 2: Local Module

1. Clone the repository and add the library module to your project in `settings.gradle.kts`:
```kotlin
include(":ffmpegx")
project(":ffmpegx").projectDir = File("path/to/FFmpegX-Android/ffmpegx")
```

2. Add dependency to your app's `build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":ffmpegx"))
}
```

### Basic Setup

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var ffmpeg: FFmpeg
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize FFmpeg
        ffmpeg = FFmpeg.initialize(this)
        
        // FFmpeg auto-installs on first use, but you can do it manually
        lifecycleScope.launch {
            if (!ffmpeg.isInstalled()) {
                ffmpeg.install()
            }
        }
    }
}
```

## 🔧 Build Requirements

### For Building the Library
- **Java 17** - Required by Android Gradle Plugin 8.12+
- **Android SDK** - API level 34
- **Gradle 8.13+** - Included via wrapper

**Note**: JitPack builds handle Java 17 automatically. For local builds, install Java 17:
```bash
# macOS with Homebrew
brew install openjdk@17

# Or download from Adoptium
# https://adoptium.net/temurin/releases/?version=17
```

## 📖 Usage Examples

### Simple Command Execution

```kotlin
lifecycleScope.launch {
    // Execute any FFmpeg command
    val success = ffmpeg.execute("-i input.mp4 -c:v mpeg4 output.mp4")
    
    // With progress tracking
    ffmpeg.execute(
        command = "-i input.mp4 -vf scale=720:480 output.mp4",
        callback = object : FFmpegHelper.FFmpegCallback {
            override fun onStart() {
                Log.d("FFmpeg", "Started")
            }
            
            override fun onProgress(progress: Float, time: Long) {
                Log.d("FFmpeg", "Progress: ${(progress * 100).toInt()}%")
            }
            
            override fun onSuccess(output: String?) {
                Log.d("FFmpeg", "Success!")
            }
            
            override fun onFailure(error: String) {
                Log.e("FFmpeg", "Failed: $error")
            }
            
            override fun onFinish() {
                Log.d("FFmpeg", "Finished")
            }
            
            override fun onOutput(line: String) {
                // Process output line by line
            }
        }
    )
}
```

### Video Compression

```kotlin
lifecycleScope.launch {
    ffmpeg.operations().compressVideo(
        inputPath = "/storage/emulated/0/DCIM/video.mp4",
        outputPath = "/storage/emulated/0/DCIM/compressed.mp4",
        quality = FFmpegOperations.VideoQuality.MEDIUM, // LOW, MEDIUM, HIGH, VERY_HIGH
        callback = object : FFmpegHelper.FFmpegCallback {
            override fun onProgress(progress: Float, time: Long) {
                runOnUiThread {
                    progressBar.progress = (progress * 100).toInt()
                }
            }
            // ... other callbacks
        }
    )
}
```

### Extract Audio from Video

```kotlin
lifecycleScope.launch {
    val success = ffmpeg.operations().extractAudio(
        inputPath = videoFile.path,
        outputPath = "${videoFile.parent}/audio.mp3",
        audioFormat = FFmpegOperations.AudioFormat.MP3 // MP3, AAC, WAV, FLAC, OGG
    )
}
```

### Video Manipulation Operations

```kotlin
// Trim video (cut a segment)
lifecycleScope.launch {
    ffmpeg.operations().trimVideo(
        inputPath = "/path/to/input.mp4",
        outputPath = "/path/to/trimmed.mp4",
        startTimeSeconds = 10.0,  // Start at 10 seconds
        durationSeconds = 30.0     // Cut 30 seconds
    )
}

// Resize video
lifecycleScope.launch {
    ffmpeg.operations().resizeVideo(
        inputPath = "/path/to/input.mp4",
        outputPath = "/path/to/resized.mp4",
        width = 1280,
        height = 720,
        maintainAspectRatio = true
    )
}

// Rotate video
lifecycleScope.launch {
    ffmpeg.operations().rotateVideo(
        inputPath = "/path/to/input.mp4",
        outputPath = "/path/to/rotated.mp4",
        degrees = 90  // 90, 180, or 270
    )
}

// Change video speed
lifecycleScope.launch {
    ffmpeg.operations().changeVideoSpeed(
        inputPath = "/path/to/input.mp4",
        outputPath = "/path/to/fast.mp4",
        speed = 2.0f,  // 2x speed
        adjustAudio = true
    )
}

// Reverse video
lifecycleScope.launch {
    ffmpeg.operations().reverseVideo(
        inputPath = "/path/to/input.mp4",
        outputPath = "/path/to/reversed.mp4",
        reverseAudio = true
    )
}
```

### Advanced Operations

```kotlin
// Merge multiple videos
lifecycleScope.launch {
    ffmpeg.operations().mergeVideos(
        videoPaths = listOf(
            "/path/to/video1.mp4",
            "/path/to/video2.mp4",
            "/path/to/video3.mp4"
        ),
        outputPath = "/path/to/merged.mp4"
    )
}

// Add watermark to video
lifecycleScope.launch {
    ffmpeg.operations().addWatermark(
        videoPath = "/path/to/video.mp4",
        watermarkPath = "/path/to/logo.png",
        outputPath = "/path/to/watermarked.mp4",
        position = FFmpegOperations.WatermarkPosition.TOP_RIGHT
        // Options: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER
    )
}

// Create GIF from video
lifecycleScope.launch {
    ffmpeg.operations().createGif(
        inputPath = "/path/to/video.mp4",
        outputPath = "/path/to/output.gif",
        width = 320,
        fps = 10,
        startTime = 0.0,
        duration = 5.0
    )
}

// Extract frames as images
lifecycleScope.launch {
    ffmpeg.operations().extractFrames(
        videoPath = "/path/to/video.mp4",
        outputPattern = "/path/to/frames/frame_%04d.jpg",
        fps = 1  // Extract 1 frame per second
    )
}

// Add subtitles
lifecycleScope.launch {
    ffmpeg.operations().addSubtitles(
        videoPath = "/path/to/video.mp4",
        subtitlePath = "/path/to/subtitles.srt",
        outputPath = "/path/to/subtitled.mp4"
    )
}
```

### Get Media Information

```kotlin
lifecycleScope.launch {
    val mediaInfo = ffmpeg.getMediaInfo("/path/to/media.mp4")
    
    mediaInfo?.let { info ->
        // General information
        Log.d("Media", "Duration: ${info.duration} ms")
        Log.d("Media", "Bitrate: ${info.bitrate} bps")
        
        // Video stream information
        info.videoStreams.forEach { video ->
            Log.d("Media", "Video Codec: ${video.codec}")
            Log.d("Media", "Resolution: ${video.width}x${video.height}")
            Log.d("Media", "Frame Rate: ${video.frameRate} fps")
        }
        
        // Audio stream information
        info.audioStreams.forEach { audio ->
            Log.d("Media", "Audio Codec: ${audio.codec}")
            Log.d("Media", "Sample Rate: ${audio.sampleRate} Hz")
            Log.d("Media", "Channels: ${audio.channels}")
        }
    }
}

// Quick checks
val isVideo = ffmpeg.operations().isVideoFile("/path/to/file.mp4")
val isAudio = ffmpeg.operations().isAudioFile("/path/to/file.mp3")
val duration = ffmpeg.operations().getVideoDuration("/path/to/video.mp4")
val resolution = ffmpeg.operations().getVideoResolution("/path/to/video.mp4")
val codec = ffmpeg.operations().getVideoCodec("/path/to/video.mp4")
```

### Command Builder (Fluent API)

```kotlin
// Build complex commands with fluent API
val command = ffmpeg.commandBuilder()
    .input("/path/to/input.mp4")
    .overwriteOutput()
    .videoCodec("mpeg4")
    .videoBitrate("2M")
    .videoFilter("scale=1280:720")
    .audioCodec("aac")
    .audioBitrate("128k")
    .audioSampleRate(44100)
    .startTime(10.0)
    .duration(30.0)
    .customOption("-preset", "fast")
    .output("/path/to/output.mp4")
    .build()

lifecycleScope.launch {
    ffmpeg.execute(command)
}
```

### Session Management

```kotlin
// Execute async with session ID
val sessionId = ffmpeg.executeAsync(
    command = "-i input.mp4 -c:v mpeg4 output.mp4",
    callback = myCallback
)

// Cancel specific session
ffmpeg.cancel(sessionId)

// Cancel all running sessions
ffmpeg.cancelAll()

// Get session information
val sessionManager = ffmpeg.sessions()
val activeCount = sessionManager.getActiveSessionCount()
val allSessions = sessionManager.getAllSessions()
```

### Network Streaming

```kotlin
// Download and convert stream
lifecycleScope.launch {
    ffmpeg.execute(
        "-i https://example.com/stream.m3u8 -c copy output.mp4"
    )
}

// Stream to RTMP
lifecycleScope.launch {
    ffmpeg.execute(
        "-re -i input.mp4 -c:v libx264 -f flv rtmp://server/live/stream"
    )
}
```

## 🏗️ Architecture

```
┌─────────────────────┐
│   Your Android App  │
└──────────┬──────────┘
           │
┌──────────▼──────────┐
│   FFmpeg.kt         │  ← Main API entry point
├─────────────────────┤
│ • FFmpegHelper      │  ← Command execution & callbacks
│ • FFmpegOperations  │  ← Pre-built video operations
│ • FFmpegInstaller   │  ← Binary extraction & management
│ • CommandBuilder    │  ← Fluent API for commands
│ • SessionManager    │  ← Multi-session handling
└──────────┬──────────┘
           │
┌──────────▼──────────┐
│    JNI Layer        │
├─────────────────────┤
│ • FFmpegNative      │  ← Native FFmpeg integration
│ • FFmpegTranscoder  │  ← Hardware transcoding
│ • MediaCodec        │  ← Hardware acceleration
└──────────┬──────────┘
           │
┌──────────▼──────────┐
│   Native Layer      │
├─────────────────────┤
│ • Static FFmpeg 6.0 │  ← Linked FFmpeg libraries
│ • LAME MP3 Encoder  │  ← High-quality MP3
│ • MediaCodec H/W    │  ← Hardware codecs
└──────────┬──────────┘
           │
┌──────────▼──────────┐
│  FFmpeg Libraries   │  ← Auto-downloaded from
│  • libavcodec       │     GitHub Release
│  • libavformat      │     (103MB compressed)
│  • libavfilter      │     Cached locally
└─────────────────────┘
```

## 📋 Permissions

Add to your `AndroidManifest.xml`:

```xml
<!-- Network operations (for streaming) -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Storage access -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" 
    android:maxSdkVersion="28" />

<!-- Android 13+ media permissions -->
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />

<!-- For HTTP downloads (if needed) -->
<application
    android:usesCleartextTraffic="true"
    ...>
```

## 🎯 Supported Formats & Codecs

### Video
| Type | Codecs |
|------|--------|
| **Decoders** | H.264, H.265/HEVC, VP8, VP9, MPEG4, MJPEG, PNG, GIF, WebP |
| **Encoders** | libx264, MPEG4, MJPEG, PNG, GIF |
| **Hardware** | MediaCodec for H.264, H.265, VP8, VP9 (encode/decode) |

### Audio
| Type | Codecs |
|------|--------|
| **Decoders** | AAC, MP3, Opus, Vorbis, FLAC, WAV, WMA, AC3, DTS |
| **Encoders** | AAC, libmp3lame (MP3), libfdk_aac, Opus, Vorbis, FLAC, WAV |

### Container Formats
| Input | Output |
|-------|--------|
| MP4, MOV, AVI, MKV, WebM, FLV, MPEGTS, HLS, M3U8, RTMP | MP4, MOV, AVI, MKV, WebM, FLV, MPEGTS, HLS, GIF |

### Filters (300+)
- **Video**: scale, crop, rotate, overlay, fade, pad, transpose, fps, drawtext, subtitles
- **Audio**: volume, aresample, atempo, equalizer, compressor, normalize

### Protocols
- File, HTTP, HTTPS, RTMP, RTSP, HLS, FTP

## ⚡ Performance Tips

1. **Use Hardware Decoders**: Append `_mediacodec` to decoder names for hardware acceleration
2. **Copy Codecs**: Use `-c copy` when you don't need re-encoding
3. **Optimal Presets**: Use MPEG4 encoder with appropriate bitrates
4. **Background Processing**: Use `executeAsync()` for long operations
5. **Cancel Operations**: Always provide cancel options for long tasks

## 🛠️ Troubleshooting

| Issue | Solution |
|-------|----------|
| **"Unknown encoder" error** | Use `mpeg4` instead of `h264`/`libx264` |
| **Slow encoding** | Use hardware decoders with `_mediacodec` suffix |
| **Large output files** | Adjust bitrate and quality settings |
| **Permission denied** | Library handles Android 10+ automatically via JNI |
| **Out of memory** | Process large files in segments |

## 📦 APK Size Optimization

The library adds approximately 40MB to your APK (both architectures). To reduce size:

1. **Use App Bundle**: Google Play will deliver only required architecture
2. **Split APKs by ABI**:
```gradle
android {
    splits {
        abi {
            enable true
            reset()
            include "arm64-v8a", "armeabi-v7a"
        }
    }
}
```

3. **Download on first use**: Implement dynamic binary download
4. **Remove unused architectures**: Keep only arm64-v8a for modern devices

## 🔧 Building Custom FFmpeg

The library automatically downloads pre-built FFmpeg libraries from GitHub Release on first build. To build custom FFmpeg:

```bash
# Clone the repository
git clone https://github.com/mzgs/FFmpegX-Android.git
cd FFmpegX-Android

# Edit build configuration
nano build-ffmpeg.sh

# Run build (requires NDK)
./build-ffmpeg.sh

# Create release package
./create-library-release.sh

# Libraries location
ffmpegx/src/main/cpp/ffmpeg-libs/
ffmpegx/src/main/cpp/lame-libs/
```

### Pre-built Libraries Include:
- FFmpeg 6.0 with GPL license
- LAME MP3 encoder (high quality)
- x264 H.264 encoder
- FDK-AAC encoder
- OpenSSL for HTTPS support
- MediaCodec hardware acceleration
- All standard codecs and filters

## 📝 Example App

The repository includes a full example app demonstrating:
- ✅ Video selection from gallery/downloads
- ✅ Video download from URL
- ✅ Media information extraction
- ✅ Video compression with progress
- ✅ Audio extraction
- ✅ Custom command execution
- ✅ Real-time progress UI

## 🤝 Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Open a Pull Request

## 📄 License

This library uses FFmpeg compiled with GPL license (includes x264, LAME, FDK-AAC).
- You must comply with GPL license when distributing your app
- See [FFmpeg License](https://ffmpeg.org/legal.html) for details
- For commercial use, consider building FFmpeg without GPL components
- The library wrapper code is provided as-is for educational purposes

## 🔗 Resources

- [FFmpeg Documentation](https://ffmpeg.org/documentation.html)
- [FFmpeg Filters](https://ffmpeg.org/ffmpeg-filters.html)
- [Android NDK](https://developer.android.com/ndk)
- [Issue Tracker](https://github.com/mzgs/FFmpegX-Android/issues)
- [JitPack Build Status](https://jitpack.io/#mzgs/FFmpegX-Android)

## 💡 Credits

Built with ❤️ using:
- FFmpeg 6.0 (GPL build with x264, LAME, FDK-AAC)
- Android NDK r27
- Kotlin Coroutines
- JNI for Android 10+ compatibility
- Automatic library download from GitHub Release

---

**Note**: This library is designed for legitimate use cases. Ensure you have necessary rights for any media you process.