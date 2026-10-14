#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
./build-ffmpeg.sh
mkdir -p releases
JAVA_HOME="${JAVA_HOME:?Set JAVA_HOME to Java 17 or newer}" ./gradlew :ffmpegx:assembleRelease
cp ffmpegx/build/outputs/aar/ffmpegx-release.aar releases/
tar czf releases/ffmpeg-android-sources.tar.gz README.md BUILD-REPORT.md build-ffmpeg.sh \
    create-library-release.sh .gitignore jitpack.yml validation scripts build.gradle.kts settings.gradle.kts gradle.properties \
    gradle gradlew gradlew.bat ffmpegx/build.gradle.kts ffmpegx/src/main/java \
    ffmpegx/src/main/AndroidManifest.xml ffmpegx/src/test ffmpegx/src/androidTest \
    app/build.gradle.kts app/src .native-build/downloads
(cd releases; shasum -a 256 ffmpegx-release.aar ffmpeg-android-sources.tar.gz > SHA256SUMS)
echo "Release artifacts and checksums are in $ROOT/releases (not uploaded)."
