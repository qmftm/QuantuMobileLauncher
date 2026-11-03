#!/bin/bash
# Builds what Minecraft 26.x needs on iPhone, next to what Amethyst already ships (run on macOS with Xcode):
#   liblwjgl.dylib liblwjgl_opengl.dylib liblwjgl_stb.dylib liblwjgl_vma.dylib   LWJGL 3.4.3 (BSD-3-Clause) + libffi (MIT)
#   libSDL3.dylib                                                                 the QuantuMobile SDL3 shim (../sdl3shim)
# OpenAL, FreeType, shaderc, SPIRV-Cross and MoltenVK come from Amethyst's Frameworks folder.
#
#   usage: build-ios-natives.sh <output dir> [lwjgl tag, default 3.4.3]
set -euo pipefail

OUT="$(mkdir -p "${1:?output dir}" && cd "$1" && pwd)"
TAG="${2:-3.4.3}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="$(xcrun --sdk iphoneos --show-sdk-path)"
MIN=14.0
CC="$(xcrun --sdk iphoneos -f clang)"
CXX="$(xcrun --sdk iphoneos -f clang++)"
TARGET="-target arm64-apple-ios$MIN -isysroot $SDK"
JH="${JAVA_HOME:?set JAVA_HOME (for jni.h)}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
JOBS="$(sysctl -n hw.ncpu)"

link_dylib() {  # link_dylib <name> <compiler> <objects dir> <extra link args...>
  local name="$1" cc="$2" dir="$3"; shift 3
  "$cc" $TARGET -dynamiclib -install_name "@rpath/lib$name.dylib" -Wl,-no_compact_unwind -o "$OUT/lib$name.dylib" "$dir"/*.o "$@"
  echo "built lib$name.dylib"
}

compile() {  # compile <objects dir> <compiler> <flags> -- <sources...>
  local dir="$1" cc="$2" flags="$3"; shift 4
  mkdir -p "$dir"
  for src in "$@"; do
    "$cc" $TARGET $flags -c "$src" -o "$dir/$(basename "${src%.*}").o" &
    while [ "$(jobs -r | wc -l)" -ge "$JOBS" ]; do sleep 0.1; done
  done
  wait
  for src in "$@"; do [ -f "$dir/$(basename "${src%.*}").o" ] || { echo "compile failed: $src"; "$cc" $TARGET $flags -c "$src" -o /dev/null; exit 1; }; done
}

# ---- libffi (static), the callback layer of LWJGL 3.4; Apple arm64 builds use trampoline tables (no writable code) ----
# (LWJGL 3.4 calls ffi_call_plan_*, which is newer than the last libffi release: pin a master commit that has it)
git init -q "$WORK/libffi" && git -C "$WORK/libffi" fetch -q --depth 1 https://github.com/libffi/libffi.git d02afc25e8ea3aac9e78e227748e1fd7c9f14c82 2>/dev/null \
  && git -C "$WORK/libffi" checkout -q FETCH_HEAD \
  || { rm -rf "$WORK/libffi"; git clone -q --depth 1 https://github.com/libffi/libffi.git "$WORK/libffi"; }
( cd "$WORK/libffi" && ./autogen.sh >/dev/null 2>&1 || ./autogen.sh >/dev/null )
mkdir "$WORK/libffi/b"
( cd "$WORK/libffi/b" && ../configure --host=aarch64-apple-darwin --disable-shared --enable-static --disable-docs \
    CC="$CC $TARGET" CCAS="$CC $TARGET" CFLAGS="-O2 -fPIC" >/dev/null && make -j"$JOBS" >/dev/null )
FFI_A="$WORK/libffi/b/.libs/libffi.a"; [ -f "$FFI_A" ]

# ---- LWJGL ----
git clone -q --depth 1 --branch "$TAG" https://github.com/LWJGL/lwjgl3.git "$WORK/lwjgl3"
M="$WORK/lwjgl3/modules/lwjgl"
# The iOS SDK has the Objective-C runtime but not macOS's umbrella header for it.
mkdir -p "$WORK/compat/objc"
printf '#include <objc/runtime.h>\n#include <objc/message.h>\n' > "$WORK/compat/objc/objc-runtime.h"
CFLAGS="-O2 -fPIC -DNDEBUG -DLWJGL_MACOS -DLWJGL_arm64 -Wno-everything -I$WORK/compat -I$JH/include -I$JH/include/darwin
 -I$M/core/src/main/c -I$M/core/src/main/c/macos -I$M/core/src/main/c/libffi -I$M/core/src/main/c/libffi/aarch64"

# core: the macOS sources, minus CoreGraphics (display APIs iOS does not have; nothing on iOS calls them)
CORE=$(ls "$M"/core/src/main/c/*.c "$M"/core/src/generated/c/*.c "$M"/core/src/generated/c/macos/*.c | grep -v CoreGraphics)
compile "$WORK/o/lwjgl" "$CC" "-x objective-c $CFLAGS" -- $CORE
link_dylib lwjgl "$CC" "$WORK/o/lwjgl" "$FFI_A" -framework CoreFoundation -framework Foundation

compile "$WORK/o/stb" "$CC" "$CFLAGS -I$M/stb/src/main/c" -- "$M"/stb/src/generated/c/*.c
link_dylib lwjgl_stb "$CC" "$WORK/o/stb"

# opengl: no CGL/WGL/GLX on iOS; GL entry points come from the renderer library at run time
GL=$(ls "$M"/opengl/src/generated/c/*.c | grep -vE "_(CGL|WGL|GLX)[A-Za-z0-9_]*\.c$")
compile "$WORK/o/opengl" "$CC" "$CFLAGS -I$M/opengl/src/main/c" -- $GL
link_dylib lwjgl_opengl "$CC" "$WORK/o/opengl"

compile "$WORK/o/vma" "$CXX" "-std=c++17 $CFLAGS -I$M/vma/src/main/c -I$M/vulkan/src/main/c" -- "$M"/vma/src/generated/c/*.cpp
link_dylib lwjgl_vma "$CXX" "$WORK/o/vma"

# ---- SDL3 shim ----
git clone -q --depth 1 --branch release-3.4.16 https://github.com/libsdl-org/SDL.git "$WORK/SDL"
SHIM="$HERE/../sdl3shim"
python3 "$SHIM/gen_stubs.py" "$WORK/sdl3stubs.c"
compile "$WORK/o/sdl" "$CC" "-O2 -fPIC -fobjc-arc -Wall -I$WORK/SDL/include" -- "$SHIM/sdl3shim.c" "$SHIM/backend_amethyst.m" "$WORK/sdl3stubs.c"
link_dylib SDL3 "$CC" "$WORK/o/sdl" -framework Foundation -framework UIKit

ls -la "$OUT"
