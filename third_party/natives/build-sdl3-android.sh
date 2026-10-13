#!/bin/bash
# Builds libSDL3.so (zlib licence) for Android arm64-v8a from the upstream sources with QuantuMobile's own small patch
# (patches/sdl3-android-quantumobile.patch: always hand out an OpenGL ES 3.2 context, allow Minecraft's second hidden
# window), and copies SDL's Java glue (org.libsdl.app, zlib) next to it.
#
#   usage: build-sdl3-android.sh <output dir> [sdl tag, default release-3.4.16]
set -euo pipefail
OUT="$(mkdir -p "${1:?output dir}" && cd "$1" && pwd)"
TAG="${2:-release-3.4.16}"
HERE="$(cd "$(dirname "$0")" && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -d "$NDK" ] || { echo "set ANDROID_NDK_HOME"; exit 1; }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
git clone --depth 1 --branch "$TAG" https://github.com/libsdl-org/SDL.git "$WORK/sdl" >/dev/null 2>&1
( cd "$WORK/sdl" && git apply "$HERE/patches/sdl3-android-quantumobile.patch" )
cmake -S "$WORK/sdl" -B "$WORK/b" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DCMAKE_BUILD_TYPE=Release -DSDL_SHARED=ON -DSDL_STATIC=OFF \
  -DSDL_TESTS=OFF -DSDL_EXAMPLES=OFF -DSDL_ANDROID_JAR=OFF -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" >/dev/null
cmake --build "$WORK/b" -j"$(nproc 2>/dev/null || echo 4)" >/dev/null
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip -o "$OUT/libSDL3.so" "$WORK/b/libSDL3.so"
mkdir -p "$OUT/java" && cp -r "$WORK/sdl/android-project/app/src/main/java/org" "$OUT/java/"
cp "$WORK/sdl/LICENSE.txt" "$OUT/SDL-LICENSE.txt"
ls -la "$OUT"
