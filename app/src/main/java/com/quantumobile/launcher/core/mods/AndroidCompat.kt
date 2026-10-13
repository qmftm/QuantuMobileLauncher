package com.quantumobile.launcher.core.mods

/**
 * Mods known not to work with this launcher's renderer (GL4ES on OpenGL ES) or its Android LWJGL build. They need desktop
 * OpenGL 3.2+/4.x or a newer LWJGL and stop the game at start-up, so the mod list marks them.
 */
object AndroidCompat {
    private val UNSUPPORTED = listOf("sodium", "iris", "embeddium", "rubidium", "oculus", "optifine", "canvas", "indium", "nvidium", "vulkanmod")

    fun likelyUnsupported(modNameOrFile: String): Boolean {
        val n = modNameOrFile.lowercase()
        return UNSUPPORTED.any { n.contains(it) }
    }
}
