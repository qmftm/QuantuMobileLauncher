#!/bin/bash
# Builds the LWJGL 3.4.x native libraries this launcher needs for Android arm64-v8a, straight from LWJGL's own sources
# (BSD-3-Clause) with the NDK, using the compiler flags of LWJGL's Linux build (config/linux/build.xml) adapted for clang.
#
#   usage: build-lwjgl-android.sh <output dir> [lwjgl tag, default 3.4.3]
#
# Needs: git, an NDK (ANDROID_NDK_HOME), a JDK (for jni.h), autoconf/automake/libtool (for libffi).
# Output: liblwjgl.so liblwjgl_stb.so liblwjgl_opengl.so liblwjgl_vma.so
set -euo pipefail

OUT="$(mkdir -p "${1:?output dir}" && cd "$1" && pwd)"
TAG="${2:-3.4.3}"
API=26
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -d "$NDK" ] || { echo "set ANDROID_NDK_HOME"; exit 1; }
BIN="$(echo "$NDK"/toolchains/llvm/prebuilt/*/bin)"
CC="$BIN/aarch64-linux-android$API-clang"
CXX="$BIN/aarch64-linux-android$API-clang++"
STRIP="$BIN/llvm-strip"
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
[ -f "$JH/include/jni.h" ] || { echo "no jni.h under $JH"; exit 1; }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
JOBS="$(nproc 2>/dev/null || echo 4)"

# libffi: LWJGL 3.4 builds its callback layer on it; only the headers ship in the LWJGL tree.
git clone --depth 1 https://github.com/libffi/libffi.git "$WORK/libffi" >/dev/null 2>&1
# (autogen.sh fails on the first run until libtoolize has copied its macros in; the second run succeeds)
( cd "$WORK/libffi" && { ./autogen.sh >/dev/null 2>&1 || ./autogen.sh >/dev/null 2>&1; } ); [ -x "$WORK/libffi/configure" ]
mkdir "$WORK/libffi/b"
( cd "$WORK/libffi/b" && ../configure --host=aarch64-linux-android --disable-shared --enable-static --disable-docs \
    CC="$CC" CCAS="$CC" CFLAGS="-O2 -fPIC" >/dev/null 2>&1 && make -j"$JOBS" >/dev/null 2>&1 )
FFI_A="$WORK/libffi/b/.libs/libffi.a"; [ -f "$FFI_A" ]

git clone --depth 1 --branch "$TAG" https://github.com/LWJGL/lwjgl3.git "$WORK/lwjgl3" >/dev/null 2>&1
M="$WORK/lwjgl3/modules/lwjgl"

# Android's jni_md.h comes from the JDK's linux directory (same data model).
CFLAGS="-O2 -fPIC -pthread -DNDEBUG -DLWJGL_LINUX -DLWJGL_arm64 -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -D_GNU_SOURCE
 -D_FILE_OFFSET_BITS=64 -Wno-everything -I$JH/include -I$JH/include/linux
 -I$M/core/src/main/c -I$M/core/src/main/c/linux -I$M/core/src/main/c/libffi -I$M/core/src/main/c/libffi/aarch64"

build() {  # build <name> <compiler> <extra link args> <extra cflags> -- <sources...>
  local name="$1" cc="$2" link="$3" extra="$4"; shift 5
  local d="$WORK/o/$name"; mkdir -p "$d"
  ( cd "$d" && $cc -c $CFLAGS $extra "$@" )
  ( cd "$d" && $cc -shared -Wl,-z,noexecstack -Wl,-z,max-page-size=16384 -o "$OUT/lib$name.so" ./*.o $link -llog -ldl )
  "$STRIP" --strip-unneeded "$OUT/lib$name.so"
  echo "built lib$name.so"
}

# core: everything but the liburing binding (bionic has no liburing; Minecraft never touches it)
CORE=$(ls "$M"/core/src/main/c/*.c "$M"/core/src/generated/c/*.c "$M"/core/src/generated/c/linux/*.c | grep -v liburing)
build lwjgl "$CC" "$FFI_A" "" -- $CORE
build lwjgl_stb "$CC" "" "-I$M/stb/src/main/c" -- "$M"/stb/src/generated/c/*.c
# opengl: no WGL/GLX on Android; GL entry points come from the GL library passed to LWJGL at run time
GL=$(ls "$M"/opengl/src/generated/c/*.c | grep -vE "_(WGL|GLX)[A-Za-z0-9_]*\.c$|_opengl_(WGL|GLX)\.c$")
build lwjgl_opengl "$CC" "" "-I$M/opengl/src/main/c" -- $GL
build lwjgl_vma "$CXX" "" "-I$M/vma/src/main/c -I$M/vulkan/src/main/c" -- "$M"/vma/src/generated/c/*.cpp
ls -la "$OUT"
