#!/bin/bash
# Builds libspirv-cross.so (SPIRV-Cross C API, Apache-2.0 / MIT) for Android arm64-v8a. Minecraft 26.x loads it through
# LWJGL's lwjgl-spvc binding, which looks for the library name "spirv-cross".
#
#   usage: build-spirv-cross-android.sh <output dir> [git ref, default main]
set -euo pipefail
OUT="$(mkdir -p "${1:?output dir}" && cd "$1" && pwd)"
REF="${2:-main}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -d "$NDK" ] || { echo "set ANDROID_NDK_HOME"; exit 1; }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
git clone --depth 1 --branch "$REF" https://github.com/KhronosGroup/SPIRV-Cross.git "$WORK/src" >/dev/null 2>&1
( cd "$WORK/src" && git rev-parse HEAD > "$OUT/spirv-cross.commit" )
cmake -S "$WORK/src" -B "$WORK/b" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
  -DSPIRV_CROSS_SHARED=ON -DSPIRV_CROSS_STATIC=OFF -DSPIRV_CROSS_CLI=OFF -DSPIRV_CROSS_ENABLE_TESTS=OFF \
  -DSPIRV_CROSS_ENABLE_C_API=ON -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" >/dev/null
cmake --build "$WORK/b" -j"$(nproc 2>/dev/null || echo 4)" >/dev/null
SO="$(find "$WORK/b" -name 'libspirv-cross-c-shared.so' | head -1)"; [ -f "$SO" ]
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip -o "$OUT/libspirv-cross.so" "$SO"
cp "$WORK/src/LICENSE" "$OUT/SPIRV-Cross-LICENSE.txt"
ls -la "$OUT"
