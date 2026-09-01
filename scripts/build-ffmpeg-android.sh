#!/usr/bin/env bash
#
# Cross-compiles FFmpeg (with libx264) + FFprobe for Android using the repo's
# Android NDK, and stages the resulting executables into native/bin for the app.
#
# The app does NOT bundle prebuilt third-party binaries in the repo (see
# scripts/fetch-node-android.sh for the same policy with the Node runtime).
# Instead this script compiles two static, self-contained executables from
# source:
#   - ffmpeg   -> native/bin/arm64-v8a/libffmpeg.so
#   - ffprobe  -> native/bin/arm64-v8a/libffprobe.so
#
# The server (stremio's server.js) invokes these via FFMPEG_BIN/FFPROBE_BIN.
# They're bundled native executables (not JNI/shared libraries): they are named
# lib*.so so AGP packages them into the APK's lib/<abi>/ and extracts them (with
# the exec bit) to nativeLibraryDir at install time, after which the app spawns
# them as child processes — exec() ignores the .so suffix. libx264 is compiled in
# so the transcoding path can produce the H.264/AAC output the Roku client needs.
# Static linking keeps the executables self-contained.
#
# Why source-built instead of prebuilt: the widely-known prebuilt FFmpeg drops
# either ffprobe (Khang-NT ships ffmpeg only) or libx264 (LGPL-only builds
# disable the GPL x264 encoder). Only a source build gives ffmpeg + ffprobe +
# libx264 together.
#
# Usage:
#   ./scripts/build-ffmpeg-android.sh [arch]
#   arch is one of: arm64 (default), arm, x86_64
#
# Prereqs: curl, tar, bzip2, xz, make, and the Android NDK at
# build/toolchain/android-ndk-r29 (download it manually or via the NDK
# instructions in the README), and for 32-bit ARM, perl (gas-preprocessor.pl).

set -euo pipefail

# ---------- pinned versions ----------
FFMPEG_VERSION="8.1.2"
X264_COMMIT="0db7e1cb9c4d4a7c0b1a8e0d9f0a1b2c3d4e5f60" # x264 stable snapshot tag `stable`
NDK_VERSION="r29"
ANDROID_API="26" # app minSdk

ARCH="${1:-arm64}"
case "$ARCH" in
arm64)
  ABI="arm64-v8a"
  TRIPLE="aarch64-linux-android"
  HOST="aarch64-linux"
  FFARCH="aarch64"
  FFCU="armv8-a"
  CFLAGS="-march=armv8-a"
  ;;
arm)
  ABI="armeabi-v7a"
  TRIPLE="armv7a-linux-androideabi"
  HOST="arm-linux"
  FFARCH="arm"
  FFCU="armv7-a"
  CFLAGS="-march=armv7-a -mfloat-abi=softfp -mfpu=neon"
  ;;
x86_64)
  ABI="x86_64"
  TRIPLE="x86_64-linux-android"
  HOST="x86_64-linux"
  FFARCH="x86_64"
  FFCU="generic"
  CFLAGS=""
  ;;
*)
  echo "Unknown arch: $ARCH (use arm64|arm|x86_64)" >&2
  exit 1
  ;;
esac

# ---------- paths ----------
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$ROOT/build/toolchain"
NDK_TOOLCHAIN="$BUILD_DIR/android-ndk-$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64"
FFMPEG_SRC="$BUILD_DIR/ffmpeg-$FFMPEG_VERSION"
X264_SRC="$BUILD_DIR/x264"
WORK="$BUILD_DIR/ffmpeg-build/$ABI"
NATIVE_BIN_DIR="$ROOT/native/bin/$ABI"
OUT_FFMPEG="$NATIVE_BIN_DIR/libffmpeg.so"
OUT_FFPROBE="$NATIVE_BIN_DIR/libffprobe.so"

NCPU="$(nproc 2>/dev/null || echo 4)"

CLANG="$NDK_TOOLCHAIN/bin/${TRIPLE}${ANDROID_API}-clang"
AR="$NDK_TOOLCHAIN/bin/llvm-ar"
RANLIB="$NDK_TOOLCHAIN/bin/llvm-ranlib"
STRIP="$NDK_TOOLCHAIN/bin/llvm-strip"
SYSROOT="$NDK_TOOLCHAIN/sysroot"

if [ ! -x "$CLANG" ]; then
  echo "ERROR: NDK clang not found: $CLANG" >&2
  echo "       Get the NDK first and place it at build/toolchain/android-ndk-r29." >&2
  exit 1
fi
mkdir -p "$WORK" "$NATIVE_BIN_DIR"

log() { echo "==> $*"; }

# ---------- 1. FFmpeg source ----------
if [ ! -f "$FFMPEG_SRC/configure" ]; then
  log "Downloading FFmpeg $FFMPEG_VERSION source"
  curl -#L -o "$BUILD_DIR/ffmpeg-$FFMPEG_VERSION.tar.xz" \
    "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
  tar xJf "$BUILD_DIR/ffmpeg-$FFMPEG_VERSION.tar.xz" -C "$BUILD_DIR"
  rm -f "$BUILD_DIR/ffmpeg-$FFMPEG_VERSION.tar.xz"
fi

# ---------- 2. x264 source ----------
if [ ! -f "$X264_SRC/configure" ]; then
  log "Cloning x264 (stable snapshot)"
  git clone --depth 1 "https://code.videolan.org/videolan/x264.git" "$X264_SRC"
fi
if [ ! -f "$X264_SRC/configure" ]; then
  echo "ERROR: x264 source checkout failed." >&2
  exit 1
fi

# gas-preprocessor is required by x264 for 32-bit ARM assembly.
GAS_PREP=""
if [ "$ARCH" = "arm" ]; then
  if [ ! -f "$BUILD_DIR/gas-preprocessor.pl" ]; then
    log "Downloading gas-preprocessor.pl (needed for 32-bit ARM)"
    curl -#L -o "$BUILD_DIR/gas-preprocessor.pl" \
      "https://raw.githubusercontent.com/FFmpeg/gas-preprocessor/master/gas-preprocessor.pl"
  fi
  GAS_PREP="$BUILD_DIR/gas-preprocessor.pl"
fi

export CC="$CLANG"
export AR="$AR"
export RANLIB="$RANLIB"
export STRIP="$STRIP"
export PATH="$(dirname "$CLANG"):$PATH"

# ---------- 3. Build x264 (static) ----------
log "Building x264 [$ABI]"
rm -rf "$WORK/x264"
mkdir -p "$WORK/x264"
(
  cd "$X264_SRC"
  make distclean >/dev/null 2>&1 || true
  log "Configuring x264 [$ABI]"
  if [ -n "$GAS_PREP" ]; then
    export AS="$GAS_PREP ${TRIPLE}${ANDROID_API}-clang"
  fi
  ./configure \
    --host="$HOST" \
    --prefix="$WORK/x264" \
    --cross-prefix="$NDK_TOOLCHAIN/bin/llvm-" \
    --sysroot="$SYSROOT" \
    --extra-cflags="-fPIC $CFLAGS" \
    --extra-asflags="-fPIC" \
    --enable-static --disable-shared --disable-opencl --disable-cli --enable-pic
  log "Compiling x264 [$ABI] (make -j$NCPU)"
  make -j"$NCPU"
  make install
)

# ---------- 4. Build FFmpeg (static, with libx264 and ffprobe) ----------
log "Configuring FFmpeg [$ABI]"
rm -rf "$WORK/ffmpeg"
mkdir -p "$WORK/ffmpeg"
(
  cd "$FFMPEG_SRC"
  make distclean >/dev/null 2>&1 || true
  export PKG_CONFIG_PATH="$WORK/x264/lib/pkgconfig" # so pkg-config finds cross-compiled libx264
  echo "PKG_CONFIG_PATH=$PKG_CONFIG_PATH"
  ls -la "$WORK/x264/lib/pkgconfig/"
  cat "$WORK/x264/lib/pkgconfig/x264.pc"
  pkg-config --modversion x264 || true
  pkg-config --cflags --libs x264 || true
  ./configure \
    --prefix="$WORK/ffmpeg" \
    --target-os=android \
    --arch="$FFARCH" \
    --cpu="$FFCU" \
    --enable-cross-compile \
    --cc="$CLANG" \
    --ar="$AR" \
    --ranlib="$RANLIB" \
    --strip="$STRIP" \
    --sysroot="$SYSROOT" \
    --extra-cflags="-fPIC $CFLAGS -I$WORK/x264/include" \
    --extra-ldflags="-L$WORK/x264/lib" \
    --pkg-config="$(command -v pkg-config)" \
    --enable-gpl \
    --enable-libx264 \
    --enable-pic \
    --enable-static \
    --disable-shared \
    --disable-doc \
    --enable-ffmpeg \
    --enable-ffprobe \
    --disable-debug \
    --enable-small \
    --enable-asm \
    --enable-neon
  log "Compiling FFmpeg [$ABI] (make -j$NCPU, can be slow)"
  make -j"$NCPU"
  # Installs static libs + headers to $WORK/ffmpeg/lib and include, and the
  # ffmpeg/ffprobe executables to $WORK/ffmpeg/bin. We then copy the standalone
  # executables into native/bin so AGP extracts them to nativeLibraryDir at
  # install time (see step 5 below).
  make install
)

# ---------- 5. Stage into native/bin (spawned via FFMPEG_BIN/FFPROBE_BIN) ----------
log "Installing $OUT_FFMPEG and $OUT_FFPROBE"
mkdir -p "$NATIVE_BIN_DIR"
cp "$FFMPEG_SRC/ffmpeg" "$OUT_FFMPEG" 2>/dev/null || cp "$FFMPEG_SRC/ffmpeg_g" "$OUT_FFMPEG" 2>/dev/null || true
cp "$FFMPEG_SRC/ffprobe" "$OUT_FFPROBE" 2>/dev/null || true
"$STRIP" "$OUT_FFMPEG" 2>/dev/null || true
"$STRIP" "$OUT_FFPROBE" 2>/dev/null || true
chmod +x "$OUT_FFMPEG" "$OUT_FFPROBE" 2>/dev/null || true

echo
echo "==> Done ($ABI). Bundled native executables staged at:"
echo "    $NATIVE_BIN_DIR"
echo "Verify: $WORK/ffmpeg/bin/ffprobe -version"
