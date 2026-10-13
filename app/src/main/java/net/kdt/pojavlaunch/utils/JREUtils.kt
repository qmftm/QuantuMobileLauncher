package net.kdt.pojavlaunch.utils

import android.view.Surface
import androidx.annotation.Keep
import org.lwjgl.glfw.CallbackBridge

/**
 * Native helpers of libpojavexec. The class name is part of the JNI symbol names
 * (`Java_net_kdt_pojavlaunch_utils_JREUtils_*`), so it must not change.
 */
@Keep
object JREUtils {
    init {
        // CallbackBridge's initializer loads libpojavexec; it must run first (JNI_OnLoad looks that class up).
        CallbackBridge.ensureLoaded()
    }

    /** Updates the dynamic linker's library search path for this process (Android's linker caches it). */
    @JvmStatic external fun setLdLibraryPath(ldLibraryPath: String)

    /** dlopen with RTLD_GLOBAL so the JRE's libraries can find each other. */
    @JvmStatic external fun dlopen(name: String): Boolean

    @JvmStatic external fun chdir(path: String): Int

    /** Hands the Android surface to the renderer. Call again whenever a new surface is created. */
    @JvmStatic external fun setupBridgeWindow(surface: Surface)

    @JvmStatic external fun releaseBridgeWindow()
}
