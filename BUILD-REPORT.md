# FFmpegX repair report

## Confirmed problems

| Problem | Impact | Repair |
| --- | --- | --- |
| The working tree lacked the Gradle wrapper/catalog/settings, Kotlin implementation, and native sources. | The supplied checkout could not build. | Recovered missing build files, app, and retained Kotlin APIs from Git HEAD without changing the pre-existing staged deletions. |
| Asset binaries were shell placeholders, despite a `.downloaded` marker. | Installation could appear successful without a usable FFmpeg. | Removed placeholders. Native binaries now come from checked source archives; Gradle rejects missing/invalid binaries. |
| The custom native runner parsed and implemented only selected commands. | Arbitrary filters/options did not have actual FFmpeg CLI semantics. | Use upstream FFmpeg and ffprobe executables, each in its own process. Removed the experimental JNI/custom-transcoder paths. |
| Native download script manufactured empty x264/OpenSSL libraries and referenced hard-coded `/tmp` locations. | Link success or advertised features could be misleading; builds depended on a previous machine. | Compile actual pinned dependencies for each ABI. No stub or fallback-success path. |
| Asset extraction tried executing from writable app files. | Android 10+ rejects this execution path for modern target SDKs. | Package PIE executables as native libraries, extract with Android's package installer, execute from `nativeLibraryDir`. |
| Only two ABIs were configured; 16 KiB support was asserted without checking the shipped files. | Emulator incompatibility and potential large-page failures. | Build all four ABIs and verify ELF class/machine/interpreter, LOAD alignment, and system-only runtime dependencies. |
| Builder returned prequoted arguments and left filter values unquoted. | Paths with spaces/apostrophes and multiword filter expressions broke. | Store raw arguments; expose list execution and a tested quote/parser round trip. Never invoke a shell. |
| FLAC/OGG extraction selected `copy`; fallback could write AAC into an MP3-named output. | Wrong codecs, invalid containers, and false success. | Select the advertised encoder directly; remove misleading fallback retries and renaming. |
| Progress parser returned 0–100 while other APIs expected 0–1; the provided duration in ProgressHandler was ignored. | Wrong UI/session progress and percentage calculations. | Fraction throughout, integer percentage only in `percentageInt`; honor supplied duration and fractional timestamps. |
| Metadata retrieval decoded the entire file with a ten-second wait and parsed stderr using regexes. | Slow probes, wrong stream counts/channel layouts, and incomplete metadata. | Dedicated ffprobe JSON, rational frame-rate parsing, exact channels, exit-code checking, cancellable async probing. |
| Async native paths generated timestamps as IDs, while cancellation called an unrelated executor. | Sessions were not reliably cancellable or marked complete. | Unique IDs, one process registry, race-safe process startup/cancellation, terminal states and session progress. |
| Concat paths were unescaped; temporary list files had no app cache directory. | Apostrophe paths and Android temporary-file handling failed. | Use app cache, escape concat paths, clean up in finally, reject unsupported newline paths. |
| Upstream default H.264 encoder selection preferred MediaCodec for filtered outputs. | An emulator accepted some operations but rejected watermark frames. | Explicitly select libx264 for convenience operations and demo video filters; arbitrary commands retain upstream defaults. |
| A single `atempo` filter was used for every speed. | Very slow rates failed; fast rates could skip samples. | Validate finite positive speeds and chain 0.5–2.0 tempo filters. |
| Subtitle input had no explicit mapping. | Automatic selection could drop subtitles. | Map video, optional audio, and subtitle input explicitly with container-appropriate encoding. |
| Demo divided progress again, omitted overwrite flags, and let malformed custom commands escape its coroutine. | Wrong progress, repeat-output failures, crashes/stuck processing state. | Fix progress, quoting, overwrite flags, and exception/finally handling. |
| OpenSSL could not find Android system trust roots by their hashed filenames. | Verified HTTPS failed despite valid system certificates. | Build a PEM bundle from Android system roots and supply it via `SSL_CERT_FILE`; keep TLS verification under FFmpeg option control. |
| The demo wrote directly to public Downloads while targeting a modern SDK. | Scoped storage rejected output writes on Android 10+. | Process in app storage, export finished output through a pending MediaStore entry, and delete incomplete exports on failure. |
| URI copy returned success when the resolver returned no stream. | The app could claim a nonexistent input was loaded. | Fail explicitly when the stream cannot be opened. |

## Scope and compatibility

The main documented API is retained. Experimental native-loader/JNI/shell APIs are removed rather than preserved with unsupported behavior. The artifact version is 3.0.0 because progress units and low-level API availability change. No version was published and no Git commit/index changes were made.

The native feature set is FFmpeg 8.1.3 plus OpenSSL 3.5.8, LAME 3.100, pinned x264, x265 4.1, libvpx 1.15.2, Opus 1.5.2, Ogg 1.3.5, Vorbis 1.3.7, WebP 1.5.0, FreeType 2.13.3, HarfBuzz 11.2.1, FriBidi 1.0.16, libass 0.17.3, dav1d 1.5.1, and libaom 3.12.1. Dependency sources have SHA-256 locks. GPLv3 is enabled; nonfree FDK-AAC is excluded. Software AAC encoding is included.

All built-in FFmpeg components whose Android dependencies are available are enabled. A full command runner does not imply every optional third-party library, hardware codec, peripheral, remote endpoint, or input/output combination is supported. Some external codec libraries are built without assembly optimizations to avoid extra assembler/toolchain dependencies; this affects performance, not codec availability.

## Validation

Final checks passed:

| Check | Result |
| --- | --- |
| Native source build | FFmpeg + ffprobe compiled for arm64-v8a, armeabi-v7a, x86_64, and x86. |
| ELF checks | All eight programs, including copies unpacked from the actual AAR/APK, have the correct ABI/interpreter, 16 KiB LOAD alignment, no text relocations, and only Android system dependencies. |
| JVM regression tests | 4 passed: argument quoting/list round trip, file extensions, progress, independent session completion/cancellation. |
| Android library instrumentation | 3 passed on Android 16/API 36 ARM64: every convenience operation/advertised format, output codecs/dimensions/timing, subtitles/text, HEVC/AV1/VP9/Opus/WebP, metadata/channels, URI copying, invalid input, and cancellation. |
| Demo instrumentation | 2 passed, including app-storage processing path and completed MediaStore export to Downloads without broad storage permission. |
| Native verifier regression | Passed rejection checks for wrong architecture, bad alignment, and an unpackaged dependency. |
| HTTPS smoke check | Successfully downloaded and decoded the FFmpeg project's WAV sample with `-tls_verify 1` and Android system roots. |
| Android artifacts | Release AAR and debug APK assembled with all four ABIs. AAR includes upstream license notices. |
| Maven metadata | Published locally; coroutines are exposed as an API dependency because public session flows use their types. No remote publication. |

The ARM64 capability inventory has 193 encoders, 528 decoders, 478 filters, and 41 distinct protocol names. Hardware entries in these lists describe compiled support, not hardware availability. Exact listings and ELF verification output are in `validation/`.

JitPack downloads checksum-pinned native binaries and publishes the library to its local Maven repository using `jitpack.yml`. Native binaries are rebuilt separately using the GitHub Actions native-release workflow. Local Gradle builds produce `ffmpegx/build/outputs/aar/ffmpegx-release.aar`; the debug app is `app/build/outputs/apk/debug/app-debug.apk`.

Native verification and the format/cancellation regressions are repeatable with the commands in README. This session tested execution on a 4 KiB-page ARM64 emulator. Other ABIs were compiled and inspected but not executed; physical 16 KiB hardware, hardware codec behavior, Android 7–15 runtime compatibility, and remote RTMP/HLS services remain untested. JitPack's remote environment was not run.

## Remaining operational constraints

- Consuming apps must use legacy JNI packaging so Android extracts the executables. Binaries are immutable APK contents; install verifies them and uninstall cancels work.
- Raw `content://` paths must first be copied to readable files. Scoped-storage permissions are the caller's responsibility.
- Concat stream copying requires compatible inputs; stream-copy cuts follow keyframes. Reverse is memory-bound.
- Callback/UI dispatch is the caller's responsibility; progress estimates use the duration FFmpeg reports and may differ when timing is transformed. Full logs are available through callbacks; completion retains the last 64 KiB.
- Software encoding works independently of device MediaCodec support. Actual hardware acceleration, physical 16 KiB devices, 32-bit devices, and service-specific streaming require their own device/server checks.
- AARs using this native feature set include GPLv3 dependencies. Dependency URLs and checksums are recorded in `scripts/native-sources.txt` and `scripts/native-sources.sha256`; `build-ffmpeg.sh` copies upstream license notices into the AAR assets.

## Cleanup

Removed the obsolete download wrapper/LFS configuration, IDE metadata, empty ProGuard configurations, unused resource colors/imports/AppCompat declaration, and template tests. Public compatibility APIs remain because consumers can call them even when the demo does not. The duplicate native-binaries archive was removed; the AAR already contains all eight binaries. Source archives, checksums, build/verification scripts, compiled JNI assets, and meaningful tests remain.

Generated build intermediates, expanded native source trees, temporary logs, and object files were cleared. Downloaded pinned sources, installed native build results, and the native build tools remain cached to avoid another complete cross-compilation. The previous device run covered the retained storage test; the removed package-name assertion supplied the second demo test. Cleanup validation does not count that template assertion as retained coverage.

Cleanup checks passed: `:ffmpegx:testDebugUnitTest`, `:ffmpegx:assembleRelease`, `:app:assembleDebug`, and compilation of both Android instrumentation suites. Four JVM regression tests passed; native verification and its rejection self-check passed. Device tests were not rerun for this deletion/configuration cleanup. Release archives/checksums were refreshed to match the cleaned source tree.
