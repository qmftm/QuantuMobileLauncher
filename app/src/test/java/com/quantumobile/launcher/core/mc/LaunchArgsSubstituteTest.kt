package com.quantumobile.launcher.core.mc

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchArgsSubstituteTest {
    private val vars = mapOf("auth_player_name" to "Steve", "version_name" to "1.21", "empty" to "")

    @Test fun replacesKnownPlaceholders() {
        assertEquals("--username Steve --version 1.21", LaunchArgs.substitute("--username \${auth_player_name} --version \${version_name}", vars))
        assertEquals("a-1.21-b", LaunchArgs.substitute("a-\${version_name}-b", vars))
        assertEquals("", LaunchArgs.substitute("\${empty}", vars))
    }

    @Test fun leavesUnknownAndMalformedTextAlone() {
        assertEquals("\${nope}", LaunchArgs.substitute("\${nope}", vars))
        assertEquals("cost \$5 {x}", LaunchArgs.substitute("cost \$5 {x}", vars))
        assertEquals("\${unclosed", LaunchArgs.substitute("\${unclosed", vars))
        assertEquals("\${}", LaunchArgs.substitute("\${}", vars))
        assertEquals("plain", LaunchArgs.substitute("plain", vars))
    }

    @Test fun libraryPathKeepsVersionValueAndAddsOurFolders() {
        val args = listOf("-Xmx1G", "-Djava.library.path=/natives/java", "-cp", "x", "Main")
        val out = LaunchArgs.ensureLibraryPath(args, listOf("/app/lib", "/lwjgl"))
        assertEquals("-Djava.library.path=/natives/java:/app/lib:/lwjgl", out[1])
        assertEquals(args.size, out.size)
        // no duplicates when it is already there, and it is added when the version sets none
        assertEquals(out, LaunchArgs.ensureLibraryPath(out, listOf("/app/lib", "/lwjgl")))
        assertEquals(listOf("-Djava.library.path=/app/lib", "-Xmx1G"), LaunchArgs.ensureLibraryPath(listOf("-Xmx1G"), listOf("/app/lib")))
    }
}
