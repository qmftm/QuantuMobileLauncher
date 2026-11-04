package com.quantumobile.launcher.core.skin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Talks to Mojang for real. Opt-in: `QML_NETWORK_TESTS=1 ./gradlew :app:testDebugUnitTest --tests '*LiveSkinTest*'`. */
class LiveSkinTest {
    private val api = SkinApi()

    /** Width and height from the PNG header (the unit-test classpath has no image decoder). */
    private fun size(png: ByteArray): Pair<Int, Int> {
        assertTrue("not a PNG", png.size > 24 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte())
        val b = ByteBuffer.wrap(png, 16, 8)
        return b.int to b.int
    }

    @Test
    fun everyPresetResolvesToAUsableImage() = runBlocking {
        assumeTrue(System.getenv("QML_NETWORK_TESTS") == "1")
        for (preset in SkinPresets.skins + SkinPresets.capes) {
            val t = api.textures(preset.player) ?: throw AssertionError("${preset.player} not found")
            val url = (if (preset.kind == SkinKind.CAPE) t.capeUrl else t.skinUrl)
                ?: throw AssertionError("${preset.player}: no ${preset.kind}")
            val (w, h) = size(api.download(url))
            if (preset.kind == SkinKind.CAPE) assertTrue("${preset.player} cape ${w}x$h", SkinLayout.isCapeSize(w, h))
            else assertTrue("${preset.player} skin ${w}x$h", SkinLayout.isSkinSize(w, h) || SkinLayout.isLegacySkinSize(w, h))
            Thread.sleep(500) // be gentle with the public API
        }
    }

    @Test
    fun anUnknownNameIsNull() = runBlocking {
        assumeTrue(System.getenv("QML_NETWORK_TESTS") == "1")
        assertEquals(null, api.textures("Zz_no_such_player_zZ"))
    }
}
