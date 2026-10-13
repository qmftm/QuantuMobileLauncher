# 26.x 용 네이티브 라이브러리

Minecraft 26.x(SDL3 + LWJGL 3.4.3)를 안드로이드(arm64-v8a)에서 돌리기 위한 라이브러리와 그 빌드 스크립트입니다.
결과물은 `app/src/main/jniLibs/arm64-v8a/` 에 들어 있습니다.

| 스크립트 | 결과물 | 원본 / 라이선스 |
|---|---|---|
| `build-lwjgl-android.sh` | `liblwjgl.so` `liblwjgl_stb.so` `liblwjgl_opengl.so` `liblwjgl_vma.so` | [LWJGL 3.4.3](https://github.com/LWJGL/lwjgl3) (BSD-3-Clause) + libffi (MIT) |
| `build-sdl3-android.sh` | `libSDL3.so` (+ `org.libsdl.app` 자바 글루) | [SDL 3.4.16](https://github.com/libsdl-org/SDL) (zlib), 패치: `patches/sdl3-android-quantumobile.patch` |
| `build-mobileglues-android.sh` | `libmobileglues.so` | [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) (LGPL-2.1), 수정 없이 빌드 |

NDK(`ANDROID_NDK_HOME`)와 JDK, git, cmake 가 필요합니다. 라이선스 전문은 `licenses/` 에 있습니다.
