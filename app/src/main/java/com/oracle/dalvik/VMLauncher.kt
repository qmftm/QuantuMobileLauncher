package com.oracle.dalvik

import androidx.annotation.Keep
import net.kdt.pojavlaunch.utils.JREUtils

/** Starts the JVM through JLI_Launch (implemented in libpojavexec). The JNI symbol name fixes this class name. */
@Keep
object VMLauncher {
    init {
        JREUtils.toString() // makes sure libpojavexec is loaded
    }

    /** Runs the `java` launcher with [args] (args[0] is the program name) and returns its exit code. */
    @JvmStatic external fun launchJVM(args: Array<String>): Int
}
