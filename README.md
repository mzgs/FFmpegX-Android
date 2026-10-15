# FFmpegX-Android

Kotlin library for running **upstream FFmpeg 8.1.3 and ffprobe** on Android 7.0+ (API 24). Commands run in separate processes, with coroutine APIs, progress callbacks, cancellation, media inspection, and helpers for common audio/video operations.

| Component | Configuration |
| --- | --- |
| Android library | `ffmpegx`, version `3.0.0` |
| Demo application | `app`, built with Jetpack Compose |
| Android SDK | Minimum 24, compile 35; demo target 35 |
| Supported ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` |
| Build configuration | Java 17, Kotlin 2.2.10, Android Gradle Plugin 9.4.1, Gradle 9.8.1 |

Native executables are downloaded from a checksum-pinned GitHub Release and are not tracked in Git. JitPack downloads them before running Gradle; local builds use the same download script. This feature set enables GPLv3 components; see [License and distribution](#license-and-distribution).

The build keeps FFmpeg's built-in codecs, filters, formats, and protocols enabled wherever their Android dependencies are available. It adds x264, x265, libvpx, libaom, dav1d, LAME, Opus, Vorbis, WebP, FreeType, HarfBuzz, FriBidi, libass, and OpenSSL. This is a concrete feature set, not a promise that every optional FFmpeg dependency or device feature exists. Inspect `-encoders`, `-decoders`, `-filters`, `-formats`, `-protocols`, and `-buildconf` for the exact inventory.

The optional libvidstab dependency is not included, so `vidstabdetect` and `vidstabtransform` are unavailable.

[Setup](#add-to-your-app) · [Quick start](#quick-start) · [Operations](#convenience-operations) · [Android storage](#files-and-android-storage) · [Build](#build-from-source) · [Checks](#run-checks)

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

**In the consuming application's Gradle file:**

```kotlin
android {
    packaging { jniLibs.useLegacyPackaging = true }
}
```

The library manifest sets `android:extractNativeLibs="true"`. Keep that setting in the merged app manifest. FFmpeg and ffprobe are PIE executables named `libffmpeg_exec.so` and `libffprobe_exec.so` so Android installs them into `applicationInfo.nativeLibraryDir`. They are executed there, never copied to a writable app directory. Do not use `System.loadLibrary()` on these executables. Extraction is required even on Android 10+; uncompressed in-APK libraries are not filesystem paths that ProcessBuilder can execute.

## Quick start

```kotlin
import com.mzgs.ffmpegx.FFmpeg

// context is your Application or Activity context; inputFile and outputFile are Files.
val ffmpeg = FFmpeg.initialize(context)

// In a coroutine. Argument lists avoid command-string quoting entirely.
val success = ffmpeg.execute(listOf(
    "-y", "-i", inputFile.absolutePath,
    "-c:v", "libx264", "-crf", "23", "-pix_fmt", "yuv420p",
    "-c:a", "aac",
    outputFile.absolutePath
))
if (success) {
    val info = ffmpeg.getMediaInfo(outputFile.absolutePath)
    val durationMs = info?.duration
    val video = info?.videoStreams?.firstOrNull()
}
```

Initialize once with an application or activity context. Call `execute` and `getMediaInfo` from a coroutine; they dispatch work to `Dispatchers.IO`. `execute` waits for completion and returns whether the process exited successfully. `getMediaInfo` returns `null` if probing fails; its duration is in milliseconds and bitrate is in bits per second. Check results before using output files. Initialization or argument errors can throw exceptions.

### Build commands

`execute(String)` and `executeAsync(String)` accept FFmpeg arguments without a leading `ffmpeg`. Quotes and backslash escapes are parsed; shell expansion, pipes, and redirects are not executed. The command builder stores raw arguments: `buildAsList()` returns arguments, and `build()` quotes them for the string API.

```kotlin
val arguments = ffmpeg.commandBuilder()
    .input(inputPath).overwriteOutput()
    .videoCodec("libx264").crf(23)
    .audioCodec("aac").output(outputPath)
    .buildAsList()
ffmpeg.execute(arguments)
```

### Progress and logs

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

### Sessions and cancellation

`FFmpeg.executeAsync` is a suspend function that returns a session ID as soon as the process is scheduled. It accepts a command string; use the builder's `build()` when constructing it from paths:

```kotlin
// In a coroutine. This schedules the command without waiting for completion.
val sessionId = ffmpeg.executeAsync(ffmpeg.commandBuilder()
    .input(inputPath).overwriteOutput()
    .videoCodec("libx264").audioCodec("aac").output(outputPath)
    .build())

// Later, inspect or cancel that session.
val session = ffmpeg.sessions().getSessionInfo(sessionId)
ffmpeg.cancel(sessionId)
```

Session IDs are unique, and `sessions().activeSessions` is a `StateFlow` containing running and retained terminal sessions, including progress and completion/failure/cancellation state. Cancelling a coroutine awaiting `execute` or `getMediaInfo` stops its process. After `executeAsync` returns, use `cancel(sessionId)` or `cancelAll()` to stop scheduled work. Use `sessions().clearCompletedSessions()` to release history after inspecting it.

## Convenience operations

`ffmpeg.operations()` exposes compression (four x264 quality levels), audio extraction (MP3/AAC/WAV/FLAC/OGG), video conversion (MP4/AVI/MKV/MOV/WebM/FLV), trimming, merging, resizing, rotation, watermarks, frame extraction, GIF creation, speed changes, soft subtitles, and reversal. Media inspection also exposes file type, duration, resolution, and codec.

```kotlin
import com.mzgs.ffmpegx.FFmpegOperations

// In a coroutine; each operation returns a success Boolean.
val operations = ffmpeg.operations()
val compressed = operations.compressVideo(inputPath, compressedPath,
    quality = FFmpegOperations.VideoQuality.MEDIUM)
val extracted = operations.extractAudio(inputPath, mp3Path,
    audioFormat = FFmpegOperations.AudioFormat.MP3)
val trimmed = operations.trimVideo(inputPath, trimmedPath,
    startTimeSeconds = 5.0, durationSeconds = 10.0)
```

- Output paths must be writable. The operations overwrite output files as documented; FFmpeg rejects input/output paths that are the same.
- Merge uses concat stream copying: inputs must have compatible codecs, time bases, and stream layouts. Trim uses stream copying and is limited by keyframes; use an explicit transcoding command for frame-accurate cuts.
- Resize requires positive, even H.264 dimensions. Speed is finite and positive; audio tempo filters are chained for speeds outside 0.5–2.0.
- `addSubtitles` adds a selectable stream, mapping it explicitly. MP4/MOV use `mov_text`; MKV uses SRT. Burn subtitles with the upstream `subtitles` filter and `fontsdir=/system/fonts` or an app-provided font directory. Select a font present in that directory, for example `force_style='Fontname=Roboto'` with Android system fonts. A missing font such as Arial on the tested emulator can leave subtitles invisible even when the command succeeds. Other containers must support the chosen subtitle codec.
- Reverse buffers media in memory. Long clips require enough memory or a caller-managed segmented workflow.
- MediaCodec support is compiled with the NDK backend. Available hardware codecs and accepted dimensions/pixel formats depend on the device. Use `-c:v h264_mediacodec -ndk_codec 1` explicitly when appropriate; software encoding is selected explicitly for convenience operations. Raw FFmpeg commands should also specify the intended encoder; upstream automatic H.264 selection can prefer MediaCodec.
- Network protocols require Internet access. FFmpeg options control TLS verification (`-tls_verify 1`); the executor supplies a PEM bundle of Android's system roots through `SSL_CERT_FILE`. Provide `-ca_file` for a custom CA. Arbitrary commands retain upstream FFmpeg option defaults.
- `install()` verifies APK-packaged programs; there is no runtime binary download. `uninstall()` cancels tasks; removing these immutable binaries requires updating or uninstalling the application.

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

## Migrating to 3.0.0

The documented `FFmpeg`, helper, operations, command builder, installer, session manager, progress, exception, and utility APIs remain. Progress now consistently uses a fraction; remove any extra division by 100 in callers. `buildAsList()` now returns raw arguments. Low-level experimental JNI/shell/native loader classes and the partial custom C transcoder are removed; callers using those internals must migrate to `FFmpeg.execute`. No JNI implementation pretends to support arbitrary commands.

## Build from source

Requirements: Java 17, Android SDK 35, Python 3, and curl. The native download URL and SHA-256 checksum are pinned in [scripts/native-release.json](scripts/native-release.json). The script checks the archive checksum, all eight executables, and license notices before replacing the installed binaries.

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android/sdk
sdkmanager "platforms;android-35"
python3 scripts/download-native.py
./gradlew :ffmpegx:assembleRelease :app:assembleDebug
```

A normal Gradle build verifies all four ABIs; it fails if any executable is missing, has the wrong architecture, requires an unpackaged library, or lacks 16 KiB segment alignment. Downloads are cached by checksum in `.native-build/prebuilt/` and verified again on every run. To install binaries without network access, use `python3 scripts/download-native.py --archive /path/to/ffmpeg-android.tar.gz` with a copy of the pinned archive. Gradle and Android SDK dependencies must also be cached for a fully offline build.

The AAR is `ffmpegx/build/outputs/aar/ffmpegx-release.aar`; the demo APK is `app/build/outputs/apk/debug/app-debug.apk`. To install the demo on a connected device or emulator, run `./gradlew :app:installDebug`.

JitPack uses [jitpack.yml](jitpack.yml) to download the pinned binaries and run `./gradlew :ffmpegx:publishToMavenLocal`. The build helpers support its legacy Python runtime, and Gradle installs the required Android SDK packages. It does not compile FFmpeg or require the NDK, Meson, or Ninja.

### Rebuild and release native binaries

Run the [Build native FFmpeg release](.github/workflows/native-release.yml) workflow in GitHub Actions with a new tag such as `native-ffmpeg-8.1.3-r2`. It builds all four architectures and publishes a separate native-assets release containing binaries, license notices, corresponding source archives/build instructions, checksums, and a generated `native-release.json`. Download that JSON file, replace `scripts/native-release.json`, run the download and Gradle checks, and commit the new pin before tagging an Android library release for JitPack. Use a new native tag for every update; never overwrite existing release assets.

To rebuild locally on macOS or Linux, install NDK `27.0.12077973`, SDK CMake `3.22.1`, make, Perl, tar, shasum, pkg-config, and Python venv/pip, then run:

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"
python3 -m venv .native-tools
PIP_USER=false .native-tools/bin/pip install meson==1.12.1 ninja==1.13.0
./build-ffmpeg.sh
bash scripts/package-native.sh native-ffmpeg-8.1.3-r2
```

The package command writes the native assets and pin file to `releases/native/`; it does not publish the Android library. Upstream source URLs and checksums are pinned in [scripts/native-sources.txt](scripts/native-sources.txt) and [scripts/native-sources.sha256](scripts/native-sources.sha256). Cached archives and extracted sources are refreshed when their checksum changes; clear `.native-build/work/` and `.native-build/install/` when changing dependency versions or compiler flags. `./build-ffmpeg.sh arm64-v8a` builds one ABI, but all four are required for packaging.

## Run checks

After downloading or building the native binaries, run the native checks and JVM tests:

```bash
python3 scripts/verify-native.py
python3 scripts/test-verify-native.py
python3 scripts/test-download-native.py
./gradlew :ffmpegx:testDebugUnitTest
./gradlew :app:assembleDebug :ffmpegx:assembleRelease
```

Run instrumentation on a connected Android device or emulator:

```bash
./gradlew :ffmpegx:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

The native verifier checks all eight executables; its self-check tests rejection of invalid binaries. Unit checks cover quoting, progress units, and independent process cancellation/completion. Instrumentation uses generated real video/audio, paths containing spaces and apostrophes, every convenience operation, every advertised audio/video format, x265/AV1 encoding, metadata, invalid input, and asynchronous cancellation.

Previous validation is summarized in [BUILD-REPORT.md](BUILD-REPORT.md). Runtime validation was performed on an Android 16 / API 36 ARM64 emulator with 4 KiB pages. All four ABIs passed ELF inspection for 16 KiB alignment; physical 16 KiB devices, other-ABI execution, Android 7–15, hardware acceleration, and external streaming services still require runtime testing.

## License and distribution

This build enables GPL and GPLv3 components, including x264/x265 and OpenSSL 3. It does **not** enable `--enable-nonfree` or FDK-AAC. FFmpeg's native AAC encoder is included. Distributing a resulting application/AAR requires complying with the applicable GPL and bundled dependency licenses and providing corresponding source/build instructions. The pinned source list, checksums, and build script identify the native sources; keep the original license notices when distributing them.
