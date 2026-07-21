#!/usr/bin/env bash
#
# Cross-compiles ffmpeg (and libx264) for Android and installs the result into
# app/src/main/jniLibs/<abi>/libffmpeg.so.
#
# The output is an *executable*, not a library. Since Android 10 an app may only exec
# files from its native library directory, which is populated from the APK at install
# time - so naming it lib*.so and shipping it as a jniLib is the only way to run it.
#
# GPL: enabling libx264 makes the resulting binary GPL-2.0-or-later. This script and the
# pinned versions below are what satisfy the corresponding source-availability obligation;
# see LICENSES/README.md before shipping a build.
#
# Usage:  NDK=~/android-ndk-r27c ./tools/build-ffmpeg.sh [abi ...]
#
set -euo pipefail

FFMPEG_TAG="${FFMPEG_TAG:-n7.1}"
X264_TAG="${X264_TAG:-stable}"
MIN_API="${MIN_API:-28}"          # must match minSdk in app/build.gradle.kts

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${WORK:-$HOME/otl}"
SRC="${SRC:-$WORK/src}"
NDK="${NDK:-$WORK/android-ndk-r27c}"
JNILIBS="${JNILIBS:-$ROOT/app/src/main/jniLibs}"

ABIS=("${@:-arm64-v8a armeabi-v7a}")
read -r -a ABIS <<< "${ABIS[@]}"

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TOOLCHAIN" ] || { echo "NDK toolchain not found at $TOOLCHAIN"; exit 1; }
export PATH="$TOOLCHAIN/bin:$PATH"

configure_abi() {
  case "$1" in
    arm64-v8a)
      ARCH=aarch64; CPU=armv8-a; TRIPLE=aarch64-linux-android
      CC_PREFIX=aarch64-linux-android; X264_HOST=aarch64-linux ;;
    armeabi-v7a)
      ARCH=arm; CPU=armv7-a; TRIPLE=arm-linux-androideabi
      CC_PREFIX=armv7a-linux-androideabi; X264_HOST=arm-linux ;;
    *) echo "unsupported abi: $1"; exit 1 ;;
  esac
  export CC="$TOOLCHAIN/bin/${CC_PREFIX}${MIN_API}-clang"
  export CXX="$TOOLCHAIN/bin/${CC_PREFIX}${MIN_API}-clang++"
  export AR="$TOOLCHAIN/bin/llvm-ar"
  export NM="$TOOLCHAIN/bin/llvm-nm"
  export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  export STRIP="$TOOLCHAIN/bin/llvm-strip"
  PREFIX="$WORK/build/$1"
}

build_x264() {
  local abi="$1"
  [ -f "$PREFIX/lib/libx264.a" ] && { echo "  x264 already built"; return; }

  echo "  building x264..."
  rm -rf "$WORK/obj/x264-$abi"; mkdir -p "$WORK/obj/x264-$abi"
  pushd "$WORK/obj/x264-$abi" >/dev/null
  "$SRC/x264/configure" \
    --prefix="$PREFIX" \
    --host="$X264_HOST" \
    --cross-prefix="$TOOLCHAIN/bin/llvm-" \
    --sysroot="$TOOLCHAIN/sysroot" \
    --enable-static --enable-pic \
    --disable-cli --disable-opencl --disable-asm \
    >"$WORK/x264-$abi.log" 2>&1
  make -j"$(nproc)" >>"$WORK/x264-$abi.log" 2>&1
  make install >>"$WORK/x264-$abi.log" 2>&1
  popd >/dev/null
}

build_ffmpeg() {
  local abi="$1"
  echo "  building ffmpeg..."
  rm -rf "$WORK/obj/ffmpeg-$abi"; mkdir -p "$WORK/obj/ffmpeg-$abi"
  pushd "$WORK/obj/ffmpeg-$abi" >/dev/null

  # Point pkg-config exclusively at the cross-built prefix. LIBDIR (not just PATH) so it
  # cannot fall back to the host's x264, which would configure fine and fail to link.
  #
  # Note the explicit --pkg-config=pkg-config in the configure call below: without it
  # ffmpeg prefixes the tool name with --cross-prefix, looks for llvm-pkg-config, fails to
  # find it and silently substitutes `false` - which reports every library as missing.
  export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
  export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"

  # --disable-everything, then re-enable exactly what a timelapse render needs. Keeps the
  # binary small enough to ship in an APK: image sequences in, H.264/HEVC in an mp4 out.
  "$SRC/ffmpeg/configure" \
    --prefix="$PREFIX" \
    --target-os=android \
    --arch="$ARCH" \
    --cpu="$CPU" \
    --enable-cross-compile \
    --cross-prefix="$TOOLCHAIN/bin/llvm-" \
    --cc="$CC" --cxx="$CXX" --ar="$AR" --nm="$NM" --ranlib="$RANLIB" --strip="$STRIP" \
    --sysroot="$TOOLCHAIN/sysroot" \
    --extra-cflags="-Os -fPIC -I$PREFIX/include" \
    --extra-ldflags="-L$PREFIX/lib" \
    --pkg-config=pkg-config \
    --pkg-config-flags="--static" \
    --disable-everything \
    --disable-shared --enable-static \
    --disable-doc --disable-htmlpages --disable-manpages --disable-podpages --disable-txtpages \
    --disable-ffplay --disable-ffprobe --enable-ffmpeg \
    --disable-avdevice --disable-postproc \
    --disable-network --disable-iconv --disable-xlib --disable-libxcb \
    --disable-symver --disable-debug --enable-small \
    --enable-gpl --enable-libx264 \
    --enable-jni --enable-mediacodec \
    --enable-demuxer=image2,image2pipe,concat,mov,matroska \
    --enable-muxer=mp4,mov \
    --enable-decoder=mjpeg,png,h264,hevc \
    --enable-parser=mjpeg,png,h264,hevc \
    --enable-encoder=libx264,h264_mediacodec,hevc_mediacodec,mjpeg \
    --enable-filter=scale,crop,format,fps,setpts,null,deflicker,transpose,pad \
    --enable-protocol=file,pipe \
    --enable-bsf=h264_mp4toannexb,hevc_mp4toannexb \
    --enable-swscale --enable-avfilter \
    >"$WORK/ffmpeg-$abi.log" 2>&1 || { tail -30 "$WORK/ffmpeg-$abi.log"; exit 1; }

  make -j"$(nproc)" >>"$WORK/ffmpeg-$abi.log" 2>&1 || { tail -30 "$WORK/ffmpeg-$abi.log"; exit 1; }
  popd >/dev/null
}

install_binary() {
  local abi="$1"
  local built="$WORK/obj/ffmpeg-$abi/ffmpeg"
  [ -f "$built" ] || { echo "  ffmpeg binary missing for $abi"; exit 1; }

  mkdir -p "$JNILIBS/$abi"
  "$STRIP" -o "$JNILIBS/$abi/libffmpeg.so" "$built"
  echo "  installed $(du -h "$JNILIBS/$abi/libffmpeg.so" | cut -f1) -> $JNILIBS/$abi/libffmpeg.so"
}

echo "ffmpeg $FFMPEG_TAG + x264 $X264_TAG, minSdk $MIN_API"
for abi in "${ABIS[@]}"; do
  echo "== $abi"
  configure_abi "$abi"
  mkdir -p "$PREFIX"
  build_x264 "$abi"
  build_ffmpeg "$abi"
  install_binary "$abi"
done
echo "done"
