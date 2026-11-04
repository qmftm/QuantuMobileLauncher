package com.quantumobile.launcher.core.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class Skin3DTest {
    private val W = 240
    private val H = 300

    private fun fill(t: Texture, r: Rect, argb: Int) { for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) t[x, y] = argb }

    private val front = 0xFFFF0000.toInt(); private val back = 0xFF00FF00.toInt()
    private val right = 0xFF0000FF.toInt(); private val left = 0xFFFFFF00.toInt()
    private val top = 0xFFFF00FF.toInt()

    /** Every head face its own colour, the rest grey. */
    private fun colourful(): Texture {
        val t = Texture(64, 64)
        val p = SkinLayout.Parts(SkinModel.CLASSIC)
        fill(t, p.head.front, front); fill(t, p.head.back, back); fill(t, p.head.right, right); fill(t, p.head.left, left); fill(t, p.head.top, top)
        for (b in listOf(p.body, p.rightArm, p.leftArm, p.rightLeg, p.leftLeg)) for (r in listOf(b.front, b.back, b.left, b.right, b.top, b.bottom)) fill(t, r, 0xFF808080.toInt())
        return t
    }

    private fun count(r: Skin3DRenderer, argb: Int, shaded: Boolean = true): Int {
        // Colours are lit (darkened) a little, so match by dominant channel instead of exact value.
        fun dominant(c: Int): Int = when {
            (c ushr 24) == 0 -> -1
            (c shr 16 and 0xFF) > 150 && (c shr 8 and 0xFF) < 90 && (c and 0xFF) < 90 -> 0xFF0000   // red
            (c shr 8 and 0xFF) > 150 && (c shr 16 and 0xFF) < 90 && (c and 0xFF) < 90 -> 0x00FF00   // green
            (c and 0xFF) > 150 && (c shr 16 and 0xFF) < 90 && (c shr 8 and 0xFF) < 90 -> 0x0000FF   // blue
            (c shr 16 and 0xFF) > 150 && (c shr 8 and 0xFF) > 150 && (c and 0xFF) < 90 -> 0xFFFF00  // yellow
            (c shr 16 and 0xFF) > 150 && (c and 0xFF) > 150 && (c shr 8 and 0xFF) < 90 -> 0xFF00FF  // magenta
            else -> -2
        }
        return r.pixels.count { dominant(it) == (argb and 0xFFFFFF) }
    }

    private fun render(skin: Texture, yaw: Float, walk: Float = 0f, swing: Float = 0f, cape: Texture? = null, pitch: Float = 0.2f) =
        Skin3DRenderer(W, H).also { it.render(skin, SkinModel.CLASSIC, cape, yaw, pitch, walk, swing) }

    @Test
    fun theHeadShowsTheFaceFromTheFrontAndTheBackFromBehind() {
        val skin = colourful()
        val f = render(skin, 0f)
        assertTrue("front visible", count(f, front) > 300)
        assertEquals(0, count(f, back))
        val b = render(skin, PI.toFloat())
        assertTrue("back visible", count(b, back) > 300)
        assertEquals(0, count(b, front))
    }

    @Test
    fun turningShowsTheSidesOfTheHeadOnTheRightEnd() {
        val skin = colourful()
        // Turning the figure a quarter turn shows its right side first, the opposite way its left side.
        val r = render(skin, (PI / 2).toFloat(), pitch = 0f)
        assertTrue("right side visible", count(r, right) > 200); assertEquals(0, count(r, left))
        val l = render(skin, (-PI / 2).toFloat(), pitch = 0f)
        assertTrue("left side visible", count(l, left) > 200); assertEquals(0, count(l, right))
    }

    @Test
    fun theTopOfTheHeadShowsFromAbove() {
        val skin = colourful()
        assertTrue(count(render(skin, 0f, pitch = 0.6f), top) > 100)
        assertEquals(0, count(render(skin, 0f, pitch = -0.4f), top))
    }

    @Test
    fun faceIsOnTheCorrectSideOfTheHead() {
        // From the front, the figure's right eye (texture column 1..2 of the face) is on the viewer's left.
        val skin = colourful()
        val p = SkinLayout.Parts(SkinModel.CLASSIC)
        for (y in 0 until 8) { skin[p.head.front.x + 1, p.head.front.y + y] = 0xFF00FFFF.toInt() } // cyan stripe on the figure's right
        val r = render(skin, 0f, pitch = 0f)
        val cyan = (0 until H).flatMap { y -> (0 until W).map { x -> x to y } }.filter { (x, y) -> r.pixels[y * W + x].let { it ushr 24 != 0 && (it and 0xFF) > 150 && (it shr 8 and 0xFF) > 150 && (it shr 16 and 0xFF) < 90 } }
        assertTrue(cyan.isNotEmpty())
        assertTrue("on the viewer's left", cyan.all { it.first < W / 2 })
    }

    @Test
    fun walkingMovesTheLimbsAndStandingDoesNot() {
        val skin = colourful()
        val stand = render(skin, 0f, walk = 0f, swing = 0f)
        val standLater = render(skin, 0f, walk = 1.3f, swing = 0f)
        assertTrue(stand.pixels.contentEquals(standLater.pixels)) // no swing: the phase makes no difference
        val a = render(skin, (PI / 2).toFloat(), walk = (PI / 2).toFloat(), swing = 1f, pitch = 0f)
        val b = render(skin, (PI / 2).toFloat(), walk = (3 * PI / 2).toFloat(), swing = 1f, pitch = 0f)
        val diff = a.pixels.indices.count { a.pixels[it] != b.pixels[it] }
        assertTrue("legs and arms changed sides ($diff pixels)", diff > 400)
    }

    @Test
    fun theOuterLayerIsDrawnOverTheBaseAndCutOutWhereTransparent() {
        val skin = colourful()
        val p = SkinLayout.Parts(SkinModel.CLASSIC)
        fill(skin, p.hat.front, 0xFF0000FF.toInt()) // opaque blue hat front
        assertEquals(0, count(render(skin, 0f), front)) // the red face is hidden behind it
        assertTrue(count(render(skin, 0f), right.let { 0xFF0000FF.toInt() }) > 300)
        fill(skin, p.hat.front, 0x20FFFFFF) // nearly transparent: the face shows through
        assertTrue(count(render(skin, 0f), front) > 300)
    }

    @Test
    fun theCapeShowsFromBehindAndIsHiddenFromTheFront() {
        val skin = Texture(64, 64)
        val p = SkinLayout.Parts(SkinModel.CLASSIC)
        for (b in listOf(p.head, p.body, p.rightArm, p.leftArm, p.rightLeg, p.leftLeg)) for (r in listOf(b.front, b.back, b.left, b.right, b.top, b.bottom)) fill(skin, r, 0xFF808080.toInt())
        val cape = Texture(64, 32)
        fill(cape, SkinLayout.capeOuter, 0xFFFF0000.toInt()); fill(cape, SkinLayout.capeInner, 0xFF00FF00.toInt())
        val behind = render(skin, PI.toFloat(), cape = cape)
        assertTrue("outer cape seen from behind", count(behind, 0xFFFF0000.toInt()) > 500)
        assertEquals("inner side is not seen from behind", 0, count(behind, 0xFF00FF00.toInt()))
        val ahead = render(skin, 0f, cape = cape)
        // The outer art faces backwards, so it is never seen from the front; only the inner side's edges peek out
        // beside the legs (the cape is a pixel wider than they are on each side).
        assertEquals(0, count(ahead, 0xFFFF0000.toInt()))
        assertTrue("cape mostly hidden behind the body", count(ahead, 0xFF00FF00.toInt()) < count(behind, 0xFFFF0000.toInt()) / 5)
        // From the side the cape hangs behind the body and the inner side is not seen either.
        assertTrue(count(render(skin, (PI / 2).toFloat(), cape = cape, pitch = 0f), 0xFFFF0000.toInt()) < count(behind, 0xFFFF0000.toInt()))
    }

    @Test
    fun walkPhaseAdvancesWithTime() {
        assertEquals(0f, Skin3DRenderer.walkPhase(0f), 1e-6f)
        assertTrue(Skin3DRenderer.walkPhase(1f) > Skin3DRenderer.walkPhase(0.5f))
    }

    @Test
    fun aFrameIsFastEnoughForSmoothMotion() {
        val skin = colourful()
        val r = Skin3DRenderer(W, H)
        r.render(skin, SkinModel.CLASSIC, null, 0.3f, 0.2f, 0f, 1f) // warm up
        val t0 = System.nanoTime()
        repeat(30) { r.render(skin, SkinModel.CLASSIC, null, 0.3f + it * 0.1f, 0.2f, it * 0.3f, 1f) }
        val ms = (System.nanoTime() - t0) / 1e6 / 30
        println("3D frame: %.2f ms".format(ms))
        assertTrue("a frame took $ms ms", ms < 25)
    }
}
