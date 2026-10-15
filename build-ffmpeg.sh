#!/usr/bin/env bash
# Build the upstream command-line programs, never a subset command reimplementation.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$ROOT/.native-build"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/27.0.12077973}"
API=24
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN)}"
ABIS=("${@}")
if [ ${#ABIS[@]} -eq 0 ]; then ABIS=(arm64-v8a armeabi-v7a x86_64 x86); fi
case "$(uname -s)" in
    Darwin) TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64" ;;
    Linux) TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64" ;;
    *) echo 'Build on macOS or Linux.' >&2; exit 1 ;;
esac
export PATH="$ROOT/.native-tools/bin:$NDK/../../cmake/3.22.1/bin:$TOOLCHAIN/bin:$PATH"
for tool in curl tar make cmake pkg-config meson ninja python3 shasum perl; do
    command -v "$tool" >/dev/null || { echo "Missing build tool: $tool" >&2; exit 1; }
done
[ -d "$TOOLCHAIN" ] || { echo "NDK not found: $NDK" >&2; exit 1; }
mkdir -p "$BUILD/downloads" "$BUILD/sources"
while read -r name url; do
    archive="$BUILD/downloads/$name.tar"
    expected=$(awk -v file="$name.tar" '$2 == file {print $1}' "$ROOT/scripts/native-sources.sha256")
    [ -n "$expected" ] || { echo "Missing source checksum: $name" >&2; exit 1; }
    if [ -f "$archive" ] && [ "$(shasum -a 256 "$archive" | awk '{print $1}')" != "$expected" ]; then
        rm "$archive"
    fi
    if [ ! -f "$archive" ]; then
        curl -fL --retry 3 "$url" -o "$archive.part"
        actual=$(shasum -a 256 "$archive.part" | awk '{print $1}')
        [ "$actual" = "$expected" ] || { echo "Source checksum mismatch: $name" >&2; exit 1; }
        mv "$archive.part" "$archive"
    fi
    source="$BUILD/sources/$name"
    if [ ! -f "$source/.extracted" ] || [ "$(cat "$source/.extracted")" != "$expected" ]; then
        rm -rf "$source"
        mkdir -p "$source"
        if [ "$name" = aom ]; then tar xf "$archive" -C "$source"
        else tar xf "$archive" -C "$source" --strip-components=1; fi
        echo "$expected" > "$source/.extracted"
    fi
    notices="$ROOT/ffmpegx/src/main/assets/ffmpeg-licenses/$name"
    mkdir -p "$notices"
    for notice in "$source"/COPYING* "$source"/LICENSE* "$source"/PATENTS* "$source"/docs/FTL.TXT "$source"/docs/GPLv2.TXT; do
        if [ -f "$notice" ]; then cp "$notice" "$notices/"; fi
    done
done < "$ROOT/scripts/native-sources.txt"

# Vorbis 1.3.7 injects a removed GCC flag into i686 configure probes, including sizeof(size_t).
python3 - "$BUILD/sources/vorbis/configure" <<'VORBIS'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
p.write_text(p.read_text().replace('-mno-ieee-fp', ''))
VORBIS

cmake_build() {
    local name=$1 subdir=$2; shift 2
    cmake -S "$BUILD/sources/$name/$subdir" -B "$WORK/$name" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
        -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DBUILD_SHARED_LIBS=OFF "$@"
    cmake --build "$WORK/$name" -j "$JOBS"
    cmake --install "$WORK/$name"
}
autoconf_build() {
    local name=$1; shift
    mkdir -p "$WORK/$name"
    (cd "$WORK/$name"; "$BUILD/sources/$name/configure" --host="$HOST" \
        --prefix="$PREFIX" --disable-shared --enable-static "$@"
        # LAME otherwise tunes x86 code for the build host rather than Android.
        if [ "$name" = lame ]; then make -j "$JOBS" CFLAGS="$CFLAGS"
        else make -j "$JOBS"; fi
        make install)
}
meson_build() {
    local name=$1; shift
    meson setup "$WORK/$name" "$BUILD/sources/$name" --cross-file "$WORK/cross.ini" \
        --prefix "$PREFIX" --libdir lib --default-library static --buildtype release \
        --wrap-mode nodownload "$@"
    meson compile -C "$WORK/$name" -j "$JOBS"
    meson install -C "$WORK/$name"
}
# shortcut: cache stamps assume unchanged pins/options; clear work/install when changing either.
build_one() {
    local name=$1; shift
    if [ -f "$PREFIX/.built-$name" ]; then return; fi
    echo "Building $name for $ABI"
    "$@"
    touch "$PREFIX/.built-$name"
}
build_ssl() {
    mkdir -p "$WORK/openssl"
    (cd "$WORK/openssl"
        env CC=clang "$BUILD/sources/openssl/Configure" "$SSL_ARCH" \
            --prefix="$PREFIX" --libdir=lib --openssldir=/system/etc/security \
            no-shared no-tests no-apps -D__ANDROID_API__="$API" -fPIC
        make -j "$JOBS"; make install_sw)
}
build_x264() {
    mkdir -p "$WORK/x264"
    (cd "$WORK/x264"
        "$BUILD/sources/x264/configure" --host="$HOST" --prefix="$PREFIX" \
            --enable-static --enable-pic --disable-cli --disable-opencl --disable-asm
        make -j "$JOBS"; make install)
}
build_vpx() {
    mkdir -p "$WORK/vpx"
    (cd "$WORK/vpx"
        "$BUILD/sources/vpx/configure" --prefix="$PREFIX" --target=generic-gnu \
            --enable-pic --disable-shared --enable-static --disable-examples \
            --disable-tools --disable-docs --disable-unit-tests
        make -j "$JOBS"; make install)
}

for ABI in "${ABIS[@]}"; do
    case "$ABI" in
        arm64-v8a) TARGET=aarch64-linux-android; HOST=$TARGET; ARCH=aarch64; CPU=aarch64; SSL_ARCH=android-arm64; VPX_ARCH=arm64-android-gcc ;;
        armeabi-v7a) TARGET=armv7a-linux-androideabi; HOST=arm-linux-androideabi; ARCH=arm; CPU=arm; SSL_ARCH=android-arm; VPX_ARCH=armv7-android-gcc ;;
        x86_64) TARGET=x86_64-linux-android; HOST=$TARGET; ARCH=x86_64; CPU=x86_64; SSL_ARCH=android-x86_64; VPX_ARCH=x86_64-android-gcc ;;
        x86) TARGET=i686-linux-android; HOST=$TARGET; ARCH=x86; CPU=i686; SSL_ARCH=android-x86; VPX_ARCH=x86-android-gcc ;;
        *) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
    esac
    FFMPEG_ASM=()
    # shortcut: i686 inline assembly produces Android-forbidden text relocations; use its C paths.
    if [ "$ABI" = x86 ]; then FFMPEG_ASM=(--disable-asm); fi
    export CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
    export CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export NM="$TOOLCHAIN/bin/llvm-nm" STRIP="$TOOLCHAIN/bin/llvm-strip" AS="$CC"
    export CFLAGS='-O2 -fPIC' CXXFLAGS='-O2 -fPIC' LDFLAGS='-Wl,-z,max-page-size=16384'
    export ANDROID_NDK_ROOT="$NDK" ANDROID_NDK_HOME="$NDK"
    PREFIX="$BUILD/install/$ABI"
    WORK="$BUILD/work/$ABI"
    mkdir -p "$PREFIX" "$WORK"
    export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" PKG_CONFIG_PATH=""
    mkdir -p "$PKG_CONFIG_LIBDIR"
    # Android ships zlib but does not supply its pkg-config metadata.
    ZLIB_VERSION=$(awk '/^#define ZLIB_VERSION / {gsub(/"/, "", $3); print $3}' "$TOOLCHAIN/sysroot/usr/include/zlib.h")
    cat > "$PKG_CONFIG_LIBDIR/zlib.pc" <<ZLIB
Name: zlib
Description: Android system zlib
Version: $ZLIB_VERSION
Libs: -lz
ZLIB
    cat > "$WORK/cross.ini" <<CROSS
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = '$ARCH'
cpu = '$CPU'
endian = 'little'
[properties]
needs_exe_wrapper = true
[built-in options]
c_args = ['-O2', '-fPIC']
cpp_args = ['-O2', '-fPIC']
c_link_args = ['-Wl,-z,max-page-size=16384']
cpp_link_args = ['-static-libstdc++', '-Wl,-z,max-page-size=16384']
CROSS
    build_one openssl build_ssl
    build_one lame autoconf_build lame --disable-expopt --disable-nasm --disable-frontend --disable-decoder --with-pic
    build_one x264 build_x264
    build_one x265 cmake_build x265 source -DENABLE_SHARED=OFF -DENABLE_CLI=OFF -DENABLE_ASSEMBLY=OFF
    # x265 prefixes NDK's explicit -l: arguments a second time in its .pc file.
    python3 - "$PREFIX/lib/pkgconfig/x265.pc" <<'PC'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
p.write_text(p.read_text().replace('-l-l:', '-l:').replace('-lc++ ', '-l:libc++_static.a -l:libc++abi.a '))
PC
    build_one vpx build_vpx
    build_one opus autoconf_build opus --disable-extra-programs --disable-doc
    build_one ogg autoconf_build ogg --disable-docs
    build_one vorbis autoconf_build vorbis --with-ogg="$PREFIX"
    build_one webp cmake_build webp . -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF \
        -DWEBP_BUILD_DWEBP=OFF -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF \
        -DWEBP_BUILD_VWEBP=OFF -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_EXTRAS=OFF
    build_one freetype cmake_build freetype . -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BZIP2=ON \
        -DFT_DISABLE_PNG=ON -DFT_DISABLE_BROTLI=ON
    build_one harfbuzz meson_build harfbuzz -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled \
        -Dcairo=disabled -Dchafa=disabled -Dicu=disabled -Dtests=disabled -Ddocs=disabled -Dutilities=disabled -Dintrospection=disabled
    build_one fribidi meson_build fribidi -Ddocs=false -Dbin=false -Dtests=false
    build_one ass meson_build ass -Dfontconfig=disabled -Drequire-system-font-provider=false -Dasm=disabled
    build_one dav1d meson_build dav1d -Denable_tools=false -Denable_tests=false -Denable_asm=false
    build_one aom cmake_build aom . -DENABLE_TESTS=OFF -DENABLE_EXAMPLES=OFF -DENABLE_TOOLS=OFF \
        -DENABLE_DOCS=OFF -DCONFIG_RUNTIME_CPU_DETECT=0 -DAOM_TARGET_CPU=generic
    if [ ! -f "$PREFIX/.built-ffmpeg" ]; then
        mkdir -p "$WORK/ffmpeg"
        (cd "$WORK/ffmpeg"
            "$BUILD/sources/ffmpeg/configure" --prefix="$PREFIX" --target-os=android \
                --arch="$ARCH" --enable-cross-compile --cc="$CC" --cxx="$CXX" \
                --ar="$AR" --nm="$NM" --ranlib="$RANLIB" --strip="$STRIP" \
                --pkg-config=pkg-config --pkg-config-flags=--static \
                --enable-static --disable-shared --enable-pic --disable-doc --disable-debug \
                --disable-ffplay --disable-autodetect --disable-x86asm --enable-gpl --enable-version3 \
                --enable-openssl --enable-libmp3lame --enable-libx264 --enable-libx265 \
                --enable-libvpx --enable-libopus --enable-libvorbis --enable-libwebp \
                --enable-libfreetype --enable-libharfbuzz --enable-libfribidi --enable-libass \
                --enable-libdav1d --enable-libaom --enable-jni --enable-mediacodec --enable-zlib "${FFMPEG_ASM[@]}" \
                --extra-cflags="-O2 -fPIC -I$PREFIX/include" \
                --extra-ldflags="-L$PREFIX/lib -Wl,-z,max-page-size=16384 -static-libstdc++" \
                --extra-libs='-l:libc++_static.a -l:libc++abi.a -l:libunwind.a -lm -ldl -llog -lz'
            make -j "$JOBS"; make install)
        touch "$PREFIX/.built-ffmpeg"
    fi
    OUT="$ROOT/ffmpegx/src/main/jniLibs/$ABI"
    mkdir -p "$OUT"
    cp "$PREFIX/bin/ffmpeg" "$OUT/libffmpeg_exec.so"
    cp "$PREFIX/bin/ffprobe" "$OUT/libffprobe_exec.so"
    "$STRIP" "$OUT/libffmpeg_exec.so" "$OUT/libffprobe_exec.so"
    python3 "$ROOT/scripts/verify-native.py" "$OUT"
    echo "Built FFmpeg and ffprobe for $ABI"
done
