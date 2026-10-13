package com.quantumobile.launcher.game

import org.junit.Assert.assertEquals
import org.junit.Test

class JvmStarterTest {
    @Test fun accessTokenNeverReachesTheLog() {
        val args = listOf("-Xmx1G", "--username", "steve", "--accessToken", "SECRET.TOKEN", "--uuid", "abc")
        assertEquals(listOf("-Xmx1G", "--username", "steve", "--accessToken", "<hidden>", "--uuid", "abc"), JvmStarter.redact(args))
    }
}
