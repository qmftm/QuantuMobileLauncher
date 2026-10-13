#!/bin/bash
# Builds libmobileglues.so (MobileGlues, LGPL-2.1: https://github.com/MobileGL-Dev/MobileGlues) for Android arm64-v8a from
# its sources plus patches/mobileglues-uniform-wordboundary.patch (fixes "uniform" being matched inside identifiers such as
# `_uniform`, which broke Minecraft 26.x terrain shaders). It translates desktop OpenGL to OpenGL ES and ships its own EGL layer, which the launcher hands to
# SDL3 as the EGL library (SDL_EGL_LIBRARY), so Minecraft's OpenGL calls end up on the phone's GLES driver.
#
#   usage: build-mobileglues-android.sh <output dir> [git ref, default main]
set -euo pipefail
OUT="$(mkdir -p "${1:?output dir}" && cd "$1" && pwd)"
REF="${2:-main}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -d "$NDK" ] || { echo "set ANDROID_NDK_HOME"; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
git clone --depth 1 --branch "$REF" https://github.com/MobileGL-Dev/MobileGlues.git "$WORK/mg" >/dev/null 2>&1
( cd "$WORK/mg" && git submodule update --init --depth 1 MobileGlues-cpp/3rdparty/glslang MobileGlues-cpp/3rdparty/SPIRV-Cross \
    MobileGlues-cpp/3rdparty/xxhash MobileGlues-cpp/include/ska >/dev/null 2>&1; git rev-parse HEAD > "$OUT/MobileGlues.commit" )
git -C "$WORK/mg" apply "$HERE/patches/mobileglues-uniform-wordboundary.patch"
cmake -S "$WORK/mg/MobileGlues-cpp" -B "$WORK/b" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DCMAKE_BUILD_TYPE=Release -DANDROID_STL=c++_static \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" >/dev/null
cmake --build "$WORK/b" -j"$(nproc 2>/dev/null || echo 4)" >/dev/null
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip -o "$OUT/libmobileglues.so" "$WORK/b/libmobileglues.so"
cp "$WORK/mg/LICENSE" "$OUT/MobileGlues-LICENSE.txt"
ls -la "$OUT"
