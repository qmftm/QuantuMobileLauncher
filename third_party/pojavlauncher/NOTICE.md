# PojavLauncher components

`app/src/main/cpp/pojav/` contains code from **PojavLauncher**
(https://github.com/PojavLauncherTeam/PojavLauncher, commit `b12ad048157b3aa255d078c235dd4571e1900309`,
the project's final commit before it was discontinued), licensed under the **GNU LGPL v3** (see `LICENSE` here).

Taken from `app_pojavlauncher/src/main/jni/`: `input_bridge_v3.c`, `jre_launcher.c`, `utils.[ch]`, `log.h`, `environ/`,
`ctxbridges/` (GL4ES path only), `jvm_hooks/`, and a trimmed `egl_bridge.c`. Changes: Zink/OSMesa/Vulkan/Turnip paths and the
AWT, stdio and exit-hook modules were left out, null checks were added, and `nominal_exit` is provided by `qml_exit.c`.
This is the native half of the bridge that lets the Android LWJGL build (also from PojavLauncher, downloaded at runtime) draw to
an Android surface and receive touch/keyboard input.

The Java-side classes in `app/src/main/java/org/lwjgl/glfw/CallbackBridge.kt`, `net/kdt/pojavlaunch/utils/JREUtils.kt` and
`com/oracle/dalvik/VMLauncher.kt` implement the same JNI contract (class and method names are fixed by the native code).

Under the LGPL you may relink this library with a modified version of the code in `app/src/main/cpp/pojav/`; the whole app source is
available in this repository.
