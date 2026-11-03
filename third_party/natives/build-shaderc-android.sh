#!/bin/bash
# Builds libshaderc.so (Google shaderc, Apache-2.0, with glslang + SPIRV-Tools + SPIRV-Headers pinned by shaderc's own DEPS)
# for Android arm64-v8a. Minecraft 26.x loads it through LWJGL's lwjgl-shaderc binding (GLSL -> SPIR-V).
#
#   usage: build-shaderc-android.sh <output dir> [shaderc git ref, default main]
set -euo pipefail
OUT="$(mkdir -p "${1:?output dir}" && cd "$1" && pwd)"
REF="${2:-main}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -d "$NDK" ] || { echo "set ANDROID_NDK_HOME"; exit 1; }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
git clone --depth 1 --branch "$REF" https://github.com/google/shaderc.git "$WORK/shaderc" >/dev/null 2>&1
rev() { grep -E "^ *'$1_revision':" "$WORK/shaderc/DEPS" | head -1 | sed -E "s/.*: '([0-9a-f]+)'.*/\1/"; }
fetch() { # name url rev
  mkdir -p "$WORK/shaderc/third_party/$1"
  ( cd "$WORK/shaderc/third_party/$1" && git init -q && git remote add origin "$2" && git fetch -q --depth 1 origin "$3" && git checkout -q FETCH_HEAD )
}
fetch glslang https://github.com/KhronosGroup/glslang.git "$(rev glslang)"
fetch spirv-headers https://github.com/KhronosGroup/SPIRV-Headers.git "$(rev spirv_headers)"
fetch spirv-tools https://github.com/KhronosGroup/SPIRV-Tools.git "$(rev spirv_tools)"
( cd "$WORK/shaderc" && git rev-parse HEAD > "$OUT/shaderc.commit" )
# shaderc's third_party/CMakeLists.txt assigns a generator expression string to GLSLANG_ENABLE_INSTALL, which `if()` treats as
# true, so glslang's install/export rules always run and fail without an install set. Switch that off for this build.
sed -i 's/set(GLSLANG_ENABLE_INSTALL \$<NOT:\${SKIP_GLSLANG_INSTALL}>)/set(GLSLANG_ENABLE_INSTALL OFF)/' "$WORK/shaderc/third_party/CMakeLists.txt"
grep -q 'set(GLSLANG_ENABLE_INSTALL OFF)' "$WORK/shaderc/third_party/CMakeLists.txt"
cmake -S "$WORK/shaderc" -B "$WORK/b" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
  -DSHADERC_SKIP_TESTS=ON -DSHADERC_SKIP_EXAMPLES=ON -DSHADERC_SKIP_COPYRIGHT_CHECK=ON -DSHADERC_SKIP_INSTALL=ON \
  -DGLSLANG_ENABLE_INSTALL=OFF -DSKIP_GLSLANG_INSTALL=ON -DSKIP_SPIRV_TOOLS_INSTALL=ON -DSKIP_GOOGLETEST_INSTALL=ON -DSPIRV_SKIP_TESTS=ON -DSPIRV_SKIP_EXECUTABLES=ON -DENABLE_GLSLANG_BINARIES=OFF -DSHADERC_SKIP_EXECUTABLES=ON \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" >/dev/null
cmake --build "$WORK/b" --target shaderc_shared -j"$(nproc 2>/dev/null || echo 4)" >/dev/null
SO="$(find "$WORK/b" -name 'libshaderc_shared.so' | head -1)"; [ -f "$SO" ]
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip -o "$OUT/libshaderc.so" "$SO"
cp "$WORK/shaderc/LICENSE" "$OUT/shaderc-LICENSE.txt"
ls -la "$OUT"
