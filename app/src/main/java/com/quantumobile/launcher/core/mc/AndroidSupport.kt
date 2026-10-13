package com.quantumobile.launcher.core.mc

/**
 * Which window backend a Minecraft version needs. Up to 1.21.x the game uses LWJGL 3.3 with GLFW (PojavLauncher's bridge);
 * 26.x moved to LWJGL 3.4 with SDL3, which runs through the SDL Android backend instead.
 */
object AndroidSupport {
    private const val MAX_GLFW_LWJGL = "3.3"

    /** True when the libraries (maven names like `org.lwjgl:lwjgl:3.4.0`) need the SDL3 backend. */
    fun needsSdl(libraryNames: List<String>): Boolean {
        for (name in libraryNames) {
            val parts = name.substringBefore('@').split(':')
            if (parts.size < 3 || parts[0] != "org.lwjgl") continue
            if (parts[1] == "lwjgl-sdl") return true
            if (parts[1] == "lwjgl" && compare(parts[2], MAX_GLFW_LWJGL) > 0) return true
        }
        return false
    }

    /** Compares dotted versions on their first two numeric parts: "3.4.0" > "3.3". */
    private fun compare(a: String, b: String): Int {
        fun nums(v: String) = v.split('.', '-').take(2).map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val x = nums(a); val y = nums(b)
        for (i in 0 until 2) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
