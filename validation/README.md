# Validation evidence

Android 16 / API 36 ARM64 emulator, 4 KiB pages.

- 4 JVM regression tests passed.
- 3 library instrumentation tests passed, exercising all convenience operations and advertised formats.
- The scoped-storage Downloads export test passed before cleanup. The separate template package-name assertion was removed.
- Native ELF verification passed for FFmpeg and ffprobe in all four ABIs, including the actual AAR/APK contents.
- Native verifier rejection self-check passed.
- HTTPS input with certificate verification passed using Android system roots and the FFmpeg project's WAV sample.

Capability listings are runtime output from the built ARM64 program. They enumerate compiled support; they do not guarantee a device has every listed hardware encoder/decoder. There are 193 encoders, 528 decoders, 478 filters, and 41 distinct protocols.

Physical hardware acceleration, real 16 KiB devices, other-ABI execution, Android 7–15 execution, remote RTMP/HLS services, and JitPack were not tested. See BUILD-REPORT.md and README.md for behavior and integration requirements.
