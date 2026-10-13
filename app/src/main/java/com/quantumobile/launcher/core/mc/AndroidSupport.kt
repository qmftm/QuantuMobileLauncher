package com.quantumobile.launcher.core.mc

/**
 * Minecraft versions this launcher cannot run yet. The bridge is built on PojavLauncher's LWJGL 3.3.3 (GLFW window), so a
 * version that needs LWJGL 3.4+ or the SDL window backend fails at start-up (`NoClassDefFoundError: org/lwjgl/sdl/SDL`).
 */
object AndroidSupport {
    private const val MAX_LWJGL = "3.3"

    /** Returns why a version with these libraries (maven names like `org.lwjgl:lwjgl:3.4.0`) cannot run, or null. */
    fun unsupportedReason(libraryNames: List<String>): String? {
        for (name in libraryNames) {
            val parts = name.substringBefore('@').split(':')
            if (parts.size < 3 || parts[0] != "org.lwjgl") continue
            val artifact = parts[1]
            if (artifact == "lwjgl-sdl") return "uses the SDL window backend (LWJGL SDL)"
            if (artifact == "lwjgl" && compare(parts[2], MAX_LWJGL) > 0) return "needs LWJGL ${parts[2]}"
        }
        return null
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
