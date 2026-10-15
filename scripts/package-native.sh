#!/usr/bin/env bash
# Package an already-built native release; this does not publish the Android library.
set -euo pipefail
# macOS tar otherwise adds AppleDouble files that are not release contents.
export COPYFILE_DISABLE=1
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TAG="${1:?Usage: bash scripts/package-native.sh native-ffmpeg-VERSION-rN}"
OUT="$ROOT/releases/native"
python3 scripts/verify-native.py
# Verify corresponding source archives before including them in the release.
(cd .native-build/downloads; shasum -a 256 -c "$ROOT/scripts/native-sources.sha256")
mkdir -p "$OUT"
tar czf "$OUT/ffmpeg-android.tar.gz" -C ffmpegx/src/main jniLibs assets/ffmpeg-licenses
tar czf "$OUT/ffmpeg-android-sources.tar.gz" README.md build-ffmpeg.sh \
    scripts/native-sources.txt scripts/native-sources.sha256 scripts/verify-native.py \
    scripts/package-native.sh .native-build/downloads
python3 - "$TAG" "$OUT" <<'PY'
import hashlib, json, pathlib, sys
tag, directory = sys.argv[1], pathlib.Path(sys.argv[2])
base = f'https://github.com/mzgs/FFmpegX-Android/releases/download/{tag}'
pin = {}
checksums = []
for prefix, name in (('', 'ffmpeg-android.tar.gz'), ('sources_', 'ffmpeg-android-sources.tar.gz')):
    digest = hashlib.sha256((directory / name).read_bytes()).hexdigest()
    pin[prefix + 'url'] = f'{base}/{name}'
    pin[prefix + 'sha256'] = digest
    checksums.append(f'{digest}  {name}\n')
(directory / 'native-release.json').write_text(json.dumps(pin, indent=2) + '\n')
(directory / 'SHA256SUMS').write_text(''.join(checksums))
print(f'Native release ready in {directory}; copy native-release.json into scripts/ after publishing')
PY
