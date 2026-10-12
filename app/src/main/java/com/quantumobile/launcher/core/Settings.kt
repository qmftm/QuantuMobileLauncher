package com.quantumobile.launcher.core

import android.app.ActivityManager
import android.content.Context
import androidx.preference.PreferenceManager

/** Typed access to the values edited in the settings screen. */
class Settings(private val context: Context) {
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    val memoryMb: Int get() = prefs.getInt(KEY_MEMORY, defaultMemoryMb(context))
    val renderer: String get() = prefs.getString(KEY_RENDERER, RENDERER_GL4ES) ?: RENDERER_GL4ES
    val customRendererLib: String get() = prefs.getString(KEY_RENDERER_LIB, "").orEmpty()
    val jvmArgs: String get() = prefs.getString(KEY_JVM_ARGS, "").orEmpty()
    val resolutionPercent: Int get() = prefs.getInt(KEY_RESOLUTION, 100).coerceIn(25, 100)
    val controlsOpacity: Int get() = prefs.getInt(KEY_CONTROLS_OPACITY, 60).coerceIn(0, 100)
    val mouseSensitivity: Int get() = prefs.getInt(KEY_SENSITIVITY, 100).coerceIn(10, 300)
    val showSnapshots: Boolean get() = prefs.getBoolean(KEY_SNAPSHOTS, false)

    /** Shared object passed to LWJGL as the OpenGL implementation. */
    val rendererLibrary: String
        get() = when (renderer) {
            RENDERER_ZINK -> "libOSMesa.so"
            RENDERER_CUSTOM -> customRendererLib.ifBlank { "libGL.so" }
            else -> "libgl4es_114.so"
        }

    companion object {
        const val KEY_MEMORY = "memory_mb"
        const val KEY_RENDERER = "renderer"
        const val KEY_RENDERER_LIB = "renderer_lib"
        const val KEY_JVM_ARGS = "jvm_args"
        const val KEY_RESOLUTION = "resolution_percent"
        const val KEY_CONTROLS_OPACITY = "controls_opacity"
        const val KEY_SENSITIVITY = "mouse_sensitivity"
        const val KEY_SNAPSHOTS = "show_snapshots"

        const val RENDERER_GL4ES = "gl4es"
        const val RENDERER_ZINK = "zink"
        const val RENDERER_CUSTOM = "custom"

        /** Roughly a third of device RAM, clamped to a range Minecraft is happy with. */
        fun defaultMemoryMb(context: Context): Int {
            val am = context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val totalMb = (info.totalMem / (1024 * 1024)).toInt()
            return (totalMb / 3).coerceIn(1024, 4096) / 256 * 256
        }
    }
}
