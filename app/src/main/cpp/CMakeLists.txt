cmake_minimum_required(VERSION 3.22.1)
project(quantumobile C)

# ---- libpojavexec: Android window/input bridge for the Android LWJGL build (PojavLauncher, LGPL-3.0) ----
# Loaded twice in the game process: by the Android app (JNI_OnLoad stores the Dalvik VM) and by the JVM's GLFW class
# (stores the JVM). Symbols must stay visible: LWJGL looks up pojavInit & co. with dlsym.
add_library(pojavexec SHARED
    pojav/environ/environ.c
    pojav/utils.c
    pojav/input_bridge_v3.c
    pojav/egl_bridge.c
    pojav/jre_launcher.c
    pojav/qml_exit.c
    pojav/ctxbridges/gl_bridge.c
    pojav/ctxbridges/egl_loader.c
    pojav/ctxbridges/loader_dlopen.c
    pojav/jvm_hooks/java_exec_hooks.c
    pojav/jvm_hooks/lwjgl_dlopen_hook.c
    pojav/jvm_hooks/emui_iterator_fix_hook.c)
target_include_directories(pojavexec PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/pojav)
target_compile_definitions(pojavexec PRIVATE _GNU_SOURCE)
target_compile_options(pojavexec PRIVATE -Wno-unused-parameter -Wno-unused-function -Wno-pointer-sign -Wno-int-conversion)
# 16 KB page size compatible (Android 15+)
target_link_options(pojavexec PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(pojavexec android log dl m)

# ---- libqmlnative: launcher-specific helpers ----
add_library(qmlnative SHARED qml_native.c)
target_compile_options(qmlnative PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_options(qmlnative PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(qmlnative android log)
