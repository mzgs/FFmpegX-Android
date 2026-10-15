# FFmpegX-Android

An Android helper library for processing audio and video in your app. Compress, convert, trim, merge, resize, and edit media with Kotlin suspend functions, progress callbacks, and cancellation. Use the command builder or run FFmpeg arguments directly when you need more control.

| Requirement | Supported |
| --- | --- |
| Library version | `3.0.0` |
| Android | 7.0+ (API 24+) |
| Architectures | `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` |

FFmpeg and ffprobe are included with the library. See [License and distribution](#license-and-distribution) before distributing your app.

## Contents

- [Add to your app](#add-to-your-app)
- [Quick start](#quick-start)
- [Initialization and installation](#initialization-and-installation)
- [Execute commands](#execute-commands)
- [Command builder reference](#command-builder-reference)
- [Progress and logs](#progress-and-logs)
- [Sessions and cancellation](#sessions-and-cancellation)
- [Convenience operations](#convenience-operations)
- [Media information](#media-information)
- [Files and Android storage](#files-and-android-storage)
- [File and formatting utilities](#file-and-formatting-utilities)
- [Additional APIs](#additional-apis)
- [Progress parsing](#progress-parsing)
- [Errors and return values](#errors-and-return-values)
- [License and distribution](#license-and-distribution)

## Add to your app

Add JitPack to the consuming project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add the dependency to the app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.mzgs:FFmpegX-Android:3.0.0")
}
```

Enable native library extraction in the app's `build.gradle.kts`:

```kotlin
android {
    packaging { jniLibs.useLegacyPackaging = true }
}
```

The library sets `android:extractNativeLibs="true"` in its manifest. Keep this enabled if your app overrides the setting.

## Quick start

```kotlin
import androidx.lifecycle.lifecycleScope
import com.mzgs.ffmpegx.FFmpeg
import java.io.File
import kotlinx.coroutines.launch

// Inside Activity.onCreate; requires androidx.lifecycle:lifecycle-runtime-ktx.
val context = this
val ffmpeg = FFmpeg.initialize(context)
val inputFile = File(context.filesDir, "input.mp4") // Supply an existing video.
val outputFile = File(context.filesDir, "compressed.mp4")

lifecycleScope.launch {
    val success = ffmpeg.operations().compressVideo(
        inputFile.absolutePath,
        outputFile.absolutePath
    )
    if (success) {
        val info = ffmpeg.getMediaInfo(outputFile.absolutePath)
        val durationMs = info?.duration
        val video = info?.videoStreams?.firstOrNull()
    }
}
```

Initialize once with an application or activity context. Run media operations and `getMediaInfo` in a coroutine. Processing functions return `true` on success; check the result before using the output. `getMediaInfo` returns `null` if inspection fails, with duration in milliseconds and bitrate in bits per second. Initialization or argument errors can throw exceptions.

The examples below use `context`, an initialized `ffmpeg`, and readable `inputPath` / writable `outputPath` strings. Run suspend calls inside your coroutine scope. For operation examples, use these shared values and imports:

```kotlin
import com.mzgs.ffmpegx.FFmpegOperations.AudioFormat
import com.mzgs.ffmpegx.FFmpegOperations.VideoFormat
import com.mzgs.ffmpegx.FFmpegOperations.VideoQuality
import com.mzgs.ffmpegx.FFmpegOperations.WatermarkPosition

val inputPath = inputFile.absolutePath
val outputPath = outputFile.absolutePath
val operations = ffmpeg.operations()
fun output(name: String) = File(context.filesDir, name).absolutePath
```

## Initialization and installation

Initialize once before processing media. `install` is an optional readiness check; FFmpeg is already included with the library.

```kotlin
import com.mzgs.ffmpegx.FFmpeg
import com.mzgs.ffmpegx.FFmpegInstaller
import com.mzgs.ffmpegx.ffmpeg

val initialized = FFmpeg.isInitialized()
val ffmpeg = FFmpeg.initialize(context)
val sameInstance = FFmpeg.getInstance() // Throws if initialize was never called.
val fromContext = context.ffmpeg()     // Suspend extension; checks installation if needed.

val ready = ffmpeg.install(object : FFmpegInstaller.InstallProgressListener {
    override fun onProgressUpdate(progress: Int, message: String) {
        // Installation progress is 0–100, unlike execution's 0.0–1.0 fraction.
        println("$progress%: $message")
    }
    override fun onInstallComplete(success: Boolean, message: String) {
        println("Ready: $success ($message)")
    }
})
val present = ffmpeg.isInstalled()       // Checks whether FFmpeg is available.
```

Run synchronous readiness and version checks on an IO dispatcher:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.IO) {
    val version = ffmpeg.getVersion()           // Version description, or null.
    val verified = ffmpeg.verifyInstallation()  // Checks FFmpeg and ffprobe.
    val bytes = ffmpeg.getInstalledSize()       // Installed size in bytes.
}
val working = ffmpeg.testFFmpeg()               // Suspend installation verification.

// When shutting down work:
ffmpeg.uninstall() // Cancels all tasks; APK-owned binaries remain installed.
```

`ffmpeg.operations()`, `ffmpeg.commandBuilder()`, and `ffmpeg.sessions()` provide the operation, builder, and session APIs documented below.

## Execute commands

`execute(String)` and `executeAsync(String)` accept FFmpeg arguments without a leading `ffmpeg`. Quotes and backslash escapes are parsed; shell expansion, pipes, and redirects are not executed. The command builder stores raw arguments: `buildAsList()` returns arguments, and `build()` quotes them for the string API.

```kotlin
val arguments = ffmpeg.commandBuilder()
    .input(inputPath).overwriteOutput()
    .videoCodec("libx264").crf(23)
    .audioCodec("aac").output(outputPath)
    .buildAsList()
ffmpeg.execute(arguments)

// String overload: omit the leading "ffmpeg" executable name.
val versionOk = ffmpeg.execute("-version")

// Both overloads accept an optional callback (see Progress and logs).
val command = ffmpeg.commandBuilder()
    .input(inputPath).overwriteOutput()
    .videoCodec("libx264").audioCodec("aac").output(outputPath)
    .build()
val converted = ffmpeg.execute(command)
```

`execute` returns `true` for exit code 0 and `false` for a failed or explicitly cancelled process. It waits for completion. `executeAsync(command, callback)` returns a session ID without waiting; see [Sessions and cancellation](#sessions-and-cancellation).

## Command builder reference

Create a builder with `ffmpeg.commandBuilder()` or `FFmpegCommandBuilder()`. Every configuration method appends arguments in call order and returns the same builder. Place input options before the corresponding `input`, output options after it, and `output` last. Use a new builder for each command, or call `clear()` before reusing it.

| Method example | FFmpeg arguments / behavior |
| --- | --- |
| `builder.input(inputPath)` | `-i <path>`; repeat for multiple inputs |
| `builder.overwriteOutput()` | `-y` |
| `builder.videoCodec("libx264")` | `-c:v libx264` |
| `builder.audioCodec("aac")` | `-c:a aac` |
| `builder.videoBitrate("2M")` | `-b:v 2M` |
| `builder.audioBitrate("128k")` | `-b:a 128k` |
| `builder.frameRate(30)` | `-r 30` |
| `builder.resolution(1280, 720)` | `-s 1280x720` |
| `builder.scale(1280, -2)` | `-vf scale=1280:-2`; derive an even height |
| `builder.aspectRatio("16:9")` | `-aspect 16:9`; sets display aspect ratio |
| `builder.duration(10.0)` | `-t 10.0`; seconds |
| `builder.startTime(5.0)` | `-ss 5.0`; seconds |
| `builder.noVideo()` | `-vn` |
| `builder.noAudio()` | `-an` |
| `builder.noSubtitle()` | `-sn` |
| `builder.copyVideo()` | `-c:v copy` |
| `builder.copyAudio()` | `-c:a copy` |
| `builder.copyAllCodecs()` | `-c copy` |
| `builder.preset("fast")` | `-preset fast`; encoder-specific |
| `builder.crf(23)` | `-crf 23`; encoder-specific |
| `builder.audioChannels(2)` | `-ac 2` |
| `builder.audioSampleRate(48000)` | `-ar 48000` |
| `builder.videoFilter("scale=1280:-2,fps=30")` | `-vf <filter>` |
| `builder.audioFilter("volume=0.5")` | `-af <filter>` |
| `builder.complexFilter("[0:v][1:v]overlay=10:10")` | `-filter_complex <filter>` |
| `builder.map("0:a:0")` | `-map 0:a:0`; repeat to select multiple streams |
| `builder.customOption("-pix_fmt", "yuv420p")` | Adds an option and its value |
| `builder.customOption("-shortest")` | Adds an option without a value |
| `builder.output(outputPath)` | Appends the output path |
| `builder.build()` | Returns a quoted command `String` |
| `builder.buildAsList()` | Returns a copy of the raw `List<String>` arguments |
| `builder.clear()` | Removes all arguments and returns the builder |

The table shows independent choices, not one command to combine. Codec copying cannot be combined with filtering the same stream. Multiple `videoFilter` / `scale` calls append separate `-vf` options; combine filters into one comma-separated expression when they should all run. Paths are handled as arguments, but special characters inside FFmpeg filter expressions still need FFmpeg filter escaping.

```kotlin
import com.mzgs.ffmpegx.FFmpegCommandBuilder

val builder = FFmpegCommandBuilder()
    .overwriteOutput().startTime(5.0).input(inputPath)
    .duration(10.0)
    .videoCodec("libx264").preset("fast").crf(23)
    .videoFilter("scale=1280:-2,fps=30")
    .audioCodec("aac").audioBitrate("128k")
    .audioChannels(2).audioSampleRate(48000)
    .output(outputPath)
val success = ffmpeg.execute(builder.buildAsList())
builder.clear()
```

## Progress and logs

Pass an `FFmpegHelper.FFmpegCallback` to `execute`, `executeAsync`, or convenience operations to receive progress and logs. Callbacks run on the caller/executor threads; dispatch UI updates to the main thread. `onProgress(progress, time)` uses **0.0–1.0** and milliseconds. Percentage is unknown until FFmpeg reports a duration; successful completion reports 1.0. Input duration may differ from output duration when trimming, concatenating, or changing speed. Stream log lines with `onOutput`; completion retains the last 65,536 characters of process output.

```kotlin
import android.util.Log
import com.mzgs.ffmpegx.FFmpegHelper

// In a coroutine; arguments is the List<String> built above.
val success = ffmpeg.execute(arguments, object : FFmpegHelper.FFmpegCallback {
    override fun onStart() { }
    override fun onProgress(progress: Float, time: Long) {
        Log.d("FFmpeg", "${(progress * 100).toInt()}% at ${time}ms")
    }
    override fun onOutput(line: String) { Log.d("FFmpeg", line) }
    override fun onSuccess(output: String?) { }
    override fun onFailure(error: String) { Log.e("FFmpeg", error) }
    override fun onFinish() { }
})
```

## Sessions and cancellation

`FFmpeg.executeAsync` is a suspend function that returns a session ID as soon as the process is scheduled. It accepts a command string; use the builder's `build()` when constructing it from paths:

```kotlin
// In a coroutine. This schedules the command without waiting for completion.
val sessionId = ffmpeg.executeAsync(ffmpeg.commandBuilder()
    .input(inputPath).overwriteOutput()
    .videoCodec("libx264").audioCodec("aac").output(outputPath)
    .build())

// Later, inspect or cancel that session.
val session = ffmpeg.sessions().getSessionInfo(sessionId)
val cancelled = ffmpeg.cancel(sessionId) // false if the task is already gone.
ffmpeg.cancelAll()                      // Cancels all running sessions.
```

Session IDs are unique, and `sessions().activeSessions` is a `StateFlow` containing running and retained terminal sessions, including progress and completion/failure/cancellation state. Cancelling a coroutine awaiting `execute` or `getMediaInfo` stops its process. After `executeAsync` returns, use `cancel(sessionId)` or `cancelAll()` to stop scheduled work. Use `sessions().clearCompletedSessions()` to release history after inspecting it.

### Inspect and observe sessions

```kotlin
import kotlinx.coroutines.flow.collect

val manager = ffmpeg.sessions()
val info = manager.getSessionInfo(sessionId) // null for an unknown/removed ID.
val running = manager.isSessionRunning(sessionId)
val all = manager.getAllSessions()
val runningOnly = manager.getRunningSessions()
val runningCount = manager.getRunningSessionsCount()
val activeCount = manager.getActiveSessionCount() // Same as runningCount.

// In a lifecycle-owned coroutine; collection continues until it is cancelled.
manager.activeSessions.collect { sessions ->
    sessions.values.forEach { session ->
        println("${session.sessionId}: ${session.state}, ${session.progressPercentage}%")
    }
}
```

`SessionInfo` contains `sessionId`, `description`, `command`, `startTime`, nullable `endTime`, `state`, fractional `progress`, and `lastOutput`. Its computed properties are `duration` (elapsed milliseconds), `formattedDuration`, `progressPercentage` (integer percent), `isRunning`, `isCompleted`, `isFailed`, and `isCancelled`. States are `RUNNING`, `COMPLETED`, `FAILED`, and `CANCELLED`. `lastOutput` holds the latest line; full streamed logs come from callbacks. Media probes also create sessions.

```kotlin
val cancelled = manager.cancelSession(sessionId)
manager.cancelAllSessions()
manager.clearCompletedSessions() // Removes every non-RUNNING history entry.
manager.removeSession(sessionId) // Removes history only; does not cancel a process.
```

### Manage session records directly

The executor updates its shared manager automatically. These public methods also let you maintain records in a separate manager; adding or changing a record does not start or control a native process:

```kotlin
import com.mzgs.ffmpegx.FFmpegSessionManager

val records = FFmpegSessionManager()
records.addSession(1L, "Example record", "-version")
records.updateSessionProgress(1L, 0.5f)
records.updateSessionOutput(1L, "Example output line")
records.updateSessionState(1L, FFmpegSessionManager.SessionState.COMPLETED)
val completed = records.getSessionInfo(1L)
records.removeSession(1L)
```

## Convenience operations

Get an operations helper with `ffmpeg.operations()` or `FFmpegOperations(context)`. All 13 processing methods are suspend functions, return a success `Boolean`, accept an optional final `callback: FFmpegHelper.FFmpegCallback? = null`, and overwrite their output. Check the result before opening or exporting it. Examples below are independent and use the shared `inputPath`, `operations`, and `output(name)` from [Quick start](#quick-start).

### Compress video

Encodes H.264 video with AAC audio. Default quality is `MEDIUM`.

```kotlin
val compressed = operations.compressVideo(
    inputPath, output("compressed.mp4"), quality = VideoQuality.MEDIUM
)
```

| `VideoQuality` | x264 preset | CRF |
| --- | --- | --- |
| `LOW` | `ultrafast` | 28 |
| `MEDIUM` | `medium` | 23 |
| `HIGH` | `slow` | 18 |
| `VERY_HIGH` | `veryslow` | 15 |

Lower CRF generally improves quality and increases file size. Slower presets take longer; compression does not guarantee a smaller file for every input.

### Extract audio

Selects the first audio stream and encodes it to the chosen format. Default is MP3.

```kotlin
val extracted = operations.extractAudio(
    inputPath, output("audio.mp3"), audioFormat = AudioFormat.MP3
)
```

| `AudioFormat` | Encoder | Configured bitrate | Output extension |
| --- | --- | --- | --- |
| `MP3` | `libmp3lame` | `192k` | `.mp3` |
| `AAC` | `aac` | `192k` | `.aac` |
| `WAV` | `pcm_s16le` | `1411k` | `.wav` |
| `FLAC` | `flac` | `1411k` | `.flac` |
| `OGG` | `libvorbis` | `192k` | `.ogg` |

The helper passes the configured bitrate to FFmpeg. PCM and FLAC output size depends on the sample format and audio content; it is not a fixed target bitrate.

### Convert video

Choose the output container and the helper's matching video/audio encoders:

```kotlin
val converted = operations.convertVideo(
    inputPath, output("converted.webm"), outputFormat = VideoFormat.WEBM
)
```

| `VideoFormat` | Video encoder | Audio encoder | Output extension |
| --- | --- | --- | --- |
| `MP4` | `libx264` | `aac` | `.mp4` |
| `AVI` | `mpeg4` | `libmp3lame` | `.avi` |
| `MKV` | `libx264` | `aac` | `.mkv` |
| `MOV` | `libx264` | `aac` | `.mov` |
| `WEBM` | `libvpx` | `libvorbis` | `.webm` |
| `FLV` | `flv` | `libmp3lame` | `.flv` |

### Trim video

Start and duration are in **seconds**. This example keeps a 10-second segment starting at 5 seconds:

```kotlin
val trimmed = operations.trimVideo(
    inputPath, output("trimmed.mp4"), startTimeSeconds = 5.0, durationSeconds = 10.0
)
```

Start must be finite and nonnegative; duration must be finite and positive. Trimming copies streams, so cuts are limited by keyframes. For frame-accurate trimming, use a command that re-encodes the video.

### Merge videos

Concatenates the inputs in list order using stream copying:

```kotlin
val merged = operations.mergeVideos(
    videoPaths = listOf(inputPath, output("second.mp4")), // Both inputs must exist.
    outputPath = output("merged.mp4")
)
```

The list must be nonempty, paths cannot contain newlines, and inputs need compatible codecs, time bases, and stream layouts. The helper removes its temporary concat list when finished.

### Resize video

Default `maintainAspectRatio = true` fits the image inside the requested dimensions and pads to that exact size. Set it to `false` to stretch instead. Width and height must be positive and even.

```kotlin
val resized = operations.resizeVideo(
    inputPath, output("resized.mp4"), width = 1280, height = 720,
    maintainAspectRatio = true
)
```

### Rotate video

Supports 90, 180, or 270 degrees clockwise; other values return `false`.

```kotlin
val rotated = operations.rotateVideo(inputPath, output("rotated.mp4"), degrees = 90)
```

### Add an image watermark

The default position is `BOTTOM_RIGHT`. Corners use a 10-pixel inset. Positions are `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT`, and `CENTER`.

```kotlin
val watermarked = operations.addWatermark(
    videoPath = inputPath,
    watermarkPath = output("logo.png"), // Supply an existing image.
    outputPath = output("watermarked.mp4"),
    position = WatermarkPosition.BOTTOM_RIGHT
)
```

### Extract frames

Supply a numbered image pattern. `fps` is a positive integer and defaults to 1. Create the destination directory first.

```kotlin
val frameDirectory = File(context.filesDir, "frames").apply { mkdirs() }
val framesExtracted = operations.extractFrames(
    videoPath = inputPath,
    outputPattern = File(frameDirectory, "frame_%04d.jpg").absolutePath,
    fps = 1
)
```

### Create a GIF

Defaults: `width = 320`, `fps = 10`, `startTime = null`, `duration = null`. Height follows the input's aspect ratio. Times are in seconds.

```kotlin
val gifCreated = operations.createGif(
    inputPath, output("preview.gif"), width = 480, fps = 12,
    startTime = 2.0, duration = 3.0
)
```

Width and fps must be positive; a supplied start must be finite and nonnegative, and a supplied duration must be finite and positive.

### Change playback speed

`2.0f` doubles speed; `0.5f` halves it. Speed must be finite and positive. `adjustAudio = true` is the default and changes audio tempo along with video; `false` leaves audio tempo unchanged. Tempo filters are chained for speeds outside 0.5–2.0.

```kotlin
val spedUp = operations.changeVideoSpeed(
    inputPath, output("fast.mp4"), speed = 2.0f, adjustAudio = true
)
```

### Add selectable subtitles

Copies video/audio and adds a subtitle stream. MP4/MOV/M4V use `mov_text`; other extensions use SRT, so choose a compatible container such as MKV.

```kotlin
val subtitled = operations.addSubtitles(
    videoPath = inputPath,
    subtitlePath = output("captions.srt"), // Supply an existing subtitle file.
    outputPath = output("subtitled.mp4")
)
```

To burn subtitles into pixels, use an explicit command with FFmpeg's `subtitles` filter. Supply `fontsdir=/system/fonts` or an app font directory and choose an available font, for example `force_style='Fontname=Roboto'`. A missing font can leave subtitles invisible even when a command succeeds.

### Reverse video

Reverses video and, by default, audio. `reverseAudio = false` leaves audio in its original order. Reversal buffers media in memory; use short clips or manage segmentation yourself.

```kotlin
val reversed = operations.reverseVideo(
    inputPath, output("reversed.mp4"), reverseAudio = true
)
```

### Inspect files through operations

These five methods probe media synchronously. Call them on `Dispatchers.IO`; use `ffmpeg.getMediaInfo` for suspend inspection in one probe.

```kotlin
withContext(Dispatchers.IO) {
    val isVideo = operations.isVideoFile(inputPath)
    val isAudioOnly = operations.isAudioFile(inputPath)
    val durationMs = operations.getVideoDuration(inputPath) // 0 if unavailable.
    val resolution = operations.getVideoResolution(inputPath) // Pair<Int, Int> or null.
    val codec = operations.getVideoCodec(inputPath) // e.g. "h264", or null.
}
```

### Operation notes

- Output paths must be writable and different from input paths. When an operation copies audio or video, the output container must support those codecs.
- When writing custom commands, specify the video and audio encoders you want. Hardware encoder availability depends on the device.
- Network inputs require Internet access. For HTTPS commands, use `-tls_verify 1` to enable certificate verification and `-ca_file` if you need a custom CA.

## Media information

`getMediaInfo` runs ffprobe, waits for completion, and returns `MediaInformation?`. It returns `null` when probing fails. It exposes video and audio streams; subtitle streams and arbitrary tags are not included in this model.

```kotlin
val info = ffmpeg.getMediaInfo(inputPath)
if (info != null) {
    println("Duration: ${info.duration} ms; bitrate: ${info.bitrate} bit/s")
    info.videoStreams.forEach { video ->
        println("${video.codec}: ${video.width}x${video.height} at ${video.frameRate} fps")
    }
    info.audioStreams.forEach { audio ->
        println("${audio.codec}: ${audio.sampleRate} Hz, ${audio.channels} channels")
    }
}
```

| Model | Fields |
| --- | --- |
| `MediaInformation` | `duration: Long` (ms), `bitrate: Long` (bit/s), `videoStreams: MutableList<VideoStream>`, `audioStreams: MutableList<AudioStream>` |
| `VideoStream` | `codec: String`, `width: Int`, `height: Int`, `frameRate: Double` |
| `AudioStream` | `codec: String`, `sampleRate: Int` (Hz), `channels: Int` |

Missing numeric metadata defaults to zero. Stream-specific shortcut methods use the first video or audio stream.

## Files and Android storage

Pass readable filesystem paths to FFmpeg. For a Storage Access Framework `content://` URI, copy its contents to app storage first. The caller needs read access to the selected URI.

```kotlin
import com.mzgs.ffmpegx.FFmpegUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// In a coroutine; uri is the Uri returned by your document picker.
val input = File.createTempFile("ffmpeg-input-", ".mp4", context.cacheDir)
val output = File(context.filesDir, "converted.mp4")
try {
    val copied = withContext(Dispatchers.IO) {
        FFmpegUtils.copyUriToFile(context, uri, input)
    }
    check(copied) { "Could not read the selected video" }
    val success = ffmpeg.operations().compressVideo(input.absolutePath, output.absolutePath)
    // Export or display output only if success is true.
} finally {
    input.delete()
}
```

The demo processes in app storage and exports finished videos to Downloads through MediaStore on Android 10+, without broad media permissions. Older Android versions use the legacy storage permission.

## File and formatting utilities

`FFmpegUtils` provides synchronous filesystem helpers. Run file copying and cleanup on `Dispatchers.IO`. Path helpers only construct names; changing an extension does not convert media.

```kotlin
import com.mzgs.ffmpegx.FFmpegUtils

val extension = FFmpegUtils.getFileExtension(inputPath) // "mp4"
val renamedPath = FFmpegUtils.changeFileExtension(inputPath, "mkv")
val generatedPath = FFmpegUtils.generateOutputPath(inputPath, "compressed", "mp4")
// e.g. /.../input_compressed.mp4; omit newExtension to keep the input extension.

val durationLabel = FFmpegUtils.formatDuration(65_000L) // "01:05"
val sizeLabel = FFmpegUtils.formatFileSize(1_048_576L)  // "1.00 MB" in an English locale.

withContext(Dispatchers.IO) {
    val exists = FFmpegUtils.fileExists(inputPath)
    val bytes = FFmpegUtils.getFileSize(inputPath)
    val megabytes = FFmpegUtils.getFileSizeInMB(inputPath) // bytes / 1024²
    val videoCandidate = FFmpegUtils.isValidVideoFile(inputPath)
    val audioCandidate = FFmpegUtils.isValidAudioFile(output("audio.mp3"))
    val imageCandidate = FFmpegUtils.isValidImageFile(output("logo.png"))

    val temporary = FFmpegUtils.createTempFile(context, "preview-", "mp4")
    // With a readable document-picker Uri named uri:
    val copied = FFmpegUtils.copyUriToFile(context, uri, temporary)
    val deleted = FFmpegUtils.deleteFile(temporary.absolutePath)

    // Once no operation is using files from the utility's temp directory:
    FFmpegUtils.cleanupTempFiles(context)
}
```

`isValidVideoFile`, `isValidAudioFile`, and `isValidImageFile` check existence and a known extension; they do not inspect contents. Use media probing to identify actual audio/video streams. `getFileSize` returns bytes (normally zero for a missing file). `createTempFile` uses `cacheDir/ffmpeg_temp`; use a prefix of at least three characters and an extension without a dot. `cleanupTempFiles` deletes files in that directory. `copyUriToFile` returns `false` on failure, and its destination parent must exist. Delete temporary inputs only after their processing finishes.

## Additional APIs

For most apps, use `FFmpeg` and `ffmpeg.operations()`. The following public APIs are also available for custom integrations. Call `ffmpeg.install()` to check readiness before using execution helpers directly.

### FFmpegHelper

`execute(String)` and `execute(List<String>)` are suspend functions returning success. `executeAsync(String)` is a regular function returning a session ID. All accept the same optional `FFmpegCallback` shown in [Progress and logs](#progress-and-logs).

```kotlin
import com.mzgs.ffmpegx.FFmpegHelper

val helper = FFmpegHelper(context)
val stringResult = helper.execute("-version")
val argumentResult = helper.execute(listOf("-version"))
val helperSessionId = helper.executeAsync("-version")
val cancelled = FFmpegHelper.cancelTask(helperSessionId)
FFmpegHelper.cancelAllTasks()

// Suspend probe; dispatches its work to IO and supports coroutine cancellation.
val asyncInfo = helper.getMediaInformationAsync(inputPath)

// Synchronous probing and shortcuts: run off the main thread.
withContext(Dispatchers.IO) {
    val info = helper.getMediaInformation(inputPath) // null on failure or 30s timeout.
    val video = helper.isVideoFile(inputPath)
    val audioOnly = helper.isAudioFile(inputPath)
    val durationMs = helper.getVideoDuration(inputPath)
    val resolution = helper.getVideoResolution(inputPath)
    val videoCodec = helper.getVideoCodec(inputPath)
    val audioCodec = helper.getAudioCodec(inputPath)
    val bitrate = helper.getBitrate(inputPath)     // bit/s
    val frameRate = helper.getFrameRate(inputPath) // fps
}
```

Each shortcut starts its own probe. Use one `getMediaInformationAsync` call when you need several fields. Unavailable numeric results are zero, codec/resolution results are `null`, and file-type checks return `false`. `isAudioFile` means audio is present and no video stream is present.

### FFmpegAudioExtractor

`extractAudioWithFallback` encodes the first audio stream as 192 kbit/s MP3 with the bundled LAME encoder. It overwrites the output, accepts an optional `FFmpegCallback`, and returns a success `Boolean`; there is no additional encoder fallback.

```kotlin
import com.mzgs.ffmpegx.FFmpegAudioExtractor

val extractor = FFmpegAudioExtractor(FFmpegHelper(context))
val extracted = extractor.extractAudioWithFallback(inputPath, output("audio.mp3"))
```

### FFmpegInstaller and FFmpegTester

The listener uses `onProgressUpdate(Int, String)` and `onInstallComplete(Boolean, String)` as demonstrated in [Initialization and installation](#initialization-and-installation). `installFFmpeg` accepts that listener optionally and performs verification on IO.

```kotlin
import com.mzgs.ffmpegx.FFmpegInstaller
import com.mzgs.ffmpegx.FFmpegTester

val binaryPath = FFmpegInstaller.getFFmpegPath(context)
val probePath = FFmpegInstaller.getFFprobePath(context)
val abi = FFmpegInstaller.getDeviceABI()
val present = FFmpegInstaller.isFFmpegInstalled(context)

val installer = FFmpegInstaller(context)
val ready = installer.installFFmpeg()
withContext(Dispatchers.IO) {
    val version = installer.getInstalledVersion() // First version line, or null.
    val verified = installer.verifyInstallation()
    val bytes = installer.getInstalledSize()
}

val result = FFmpegTester.testFFmpeg(context)
println("${result.success}: ${result.message}; version=${result.version}")
installer.uninstallFFmpeg() // Cancels tasks; does not remove APK binaries.
```

`FFmpegTester.TestResult` contains `success: Boolean`, `message: String`, and `version: String?`.

### FFmpegExecutor

`execute(binaryPath, command, callback)` takes the executable path separately from its arguments and immediately returns a session ID. `onOutput` receives process logs, `onError` reports execution exceptions, and `onComplete` receives the exit code (0 for success, 255 for explicit cancellation, or another code for failure). Callbacks run on a background thread. Use `FFmpeg.execute` for the library's usual command handling and HTTPS certificate support.

```kotlin
import com.mzgs.ffmpegx.FFmpegExecutor

val executorSessionId = FFmpegExecutor.execute(
    FFmpegInstaller.getFFmpegPath(context),
    "-nostdin -version",
    object : FFmpegExecutor.ExecutorCallback {
        override fun onOutput(output: String) { println(output) }
        override fun onError(error: String) { println(error) }
        override fun onComplete(exitCode: Int) { println("Exit code: $exitCode") }
    }
)
val running = FFmpegExecutor.isRunning(executorSessionId)
val session = FFmpegExecutor.sessionManager.getSessionInfo(executorSessionId)
val cancelled = FFmpegExecutor.cancel(executorSessionId)
FFmpegExecutor.cancelAll()
```

## Progress parsing

The helper already parses execution output for its progress callback. Use `FFmpegProgressParser` or `FFmpegProgressHandler` when consuming FFmpeg log lines yourself. Both expect standard log lines containing `time=HH:MM:SS.fraction`.

```kotlin
import com.mzgs.ffmpegx.FFmpegProgressParser
import com.mzgs.ffmpegx.FFmpegProgressHandler

val parser = FFmpegProgressParser(durationMs = 10_000L)
val line = "frame= 125 fps=25.0 size= 1024kB time=00:00:05.00 bitrate=1677.7kbits/s speed=2.0x"
val progress = parser.parseProgress(line)
if (progress != null) {
    println("${progress.percentageInt}% at ${progress.formattedTime}")
    println("${progress.formattedSize}, ${progress.formattedBitrate}, ${progress.formattedSpeed}")
}
val lastTimeMs = parser.lastTimeMs // 5000
parser.reset()

val handler = FFmpegProgressHandler(totalDurationMs = 10_000L) { update ->
    println("${update.percentageInt}%: ${update.formattedEstimatedRemainingTime} remaining")
}
handler.handleOutput(line)
handler.reset()
```

Without a supplied duration, the parser learns it from a `Duration: ...` line. Until duration is known, the fractional percentage is zero. `parseProgress` returns `null` for unrelated lines and when time has not advanced. `reset` clears elapsed time and restores the originally supplied duration. The handler invokes its callback synchronously from `handleOutput`.

| Model | Raw fields | Computed properties |
| --- | --- | --- |
| `ProgressInfo` | `timeMs`, `totalDurationMs`, `percentage` (0.0–1.0), `fps`, `speed`, `bitrate` (kbit/s), `size` (bytes), `frame` | `remainingTimeMs`, `percentageInt`, `estimatedTotalTimeMs`, `estimatedRemainingTimeMs`, `formattedTime`, `formattedTotalTime`, `formattedRemainingTime`, `formattedEstimatedRemainingTime`, `formattedSize`, `formattedBitrate`, `formattedSpeed`, `formattedFps` |
| `FFmpegProgress` | `timeMs`, `totalDurationMs`, `percentage` (0.0–1.0), `bitrate` (kbit/s), `fps`, `videoFrameNumber`, `videoQuality`, `size` (bytes), `speed` | `remainingTimeMs`, `percentageInt`, `formattedTime`, `formattedTotalTime`, `formattedRemainingTime`, `estimatedRemainingTimeMs`, `formattedEstimatedRemainingTime` |

The handler sets `videoQuality` to `0f`; it does not parse encoder quality. Time labels use `HH:MM:SS`, and estimates use the parsed speed. These progress bitrates are in kbit/s, while media information reports bit/s.

## Errors and return values

Check processing `Boolean` results and nullable probe results. Use `onFailure(error)` for execution details and `onOutput(line)` for streamed logs. Coroutine cancellation propagates as `CancellationException`; rethrow it if you catch exceptions around suspend work. A command string with unclosed quotes or a trailing escape, or an invalid operation argument, can throw `IllegalArgumentException`.

```kotlin
import android.util.Log
import com.mzgs.ffmpegx.FFmpegException
import kotlinx.coroutines.CancellationException

try {
    val success = ffmpeg.execute(listOf("-version"))
    if (!success) Log.e("FFmpeg", "Command failed; inspect callback logs")
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: FFmpegException) {
    Log.e("FFmpeg", "Library error", error)
} catch (error: IllegalArgumentException) {
    Log.e("FFmpeg", "Invalid arguments", error)
}

// For a nonzero exit code obtained through the low-level executor:
val failure = FFmpegException.fromReturnCode(255) // ProcessCancelledException
```

`fromReturnCode` maps 255 to `ProcessCancelledException`, 1 to `CommandExecutionException` with a general-error message, and any other value to `CommandExecutionException` with that code. Call it for failures only; it also maps 0 to an exception.

The public exception constructors are available when integrating your own validation. Processing methods do not automatically throw each of these types:

| Constructor example | Meaning |
| --- | --- |
| `FFmpegException.InstallationException("Binaries unavailable")` | Installation verification failed |
| `FFmpegException.InvalidInputException("Input is unreadable")` | Invalid input |
| `FFmpegException.InvalidOutputException("Output directory is missing")` | Invalid output |
| `FFmpegException.CommandExecutionException("Encoding failed")` | Command failed |
| `FFmpegException.FileNotFoundException(inputPath)` | Missing file |
| `FFmpegException.InvalidFormatException("unknown")` | Invalid format |
| `FFmpegException.InsufficientPermissionsException("Read access required")` | Missing permission |
| `FFmpegException.ProcessCancelledException()` | Process cancelled |
| `FFmpegException.TimeoutException(30_000L)` | Timeout in milliseconds |

## License and distribution

The bundled FFmpeg includes GPLv3 components. Distributing an app that includes this library requires complying with the applicable GPL and bundled dependency licenses, including corresponding source and build instructions. Preserve the included license notices.
