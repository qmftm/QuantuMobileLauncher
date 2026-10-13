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
}
