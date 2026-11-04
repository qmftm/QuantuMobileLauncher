package com.quantumobile.launcher.core.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinTest {
    /** Every pixel different, so a wrong copy shows up. */
    private fun numbered(w: Int, h: Int) = Texture(w, h, IntArray(w * h) { 0xFF000000.toInt() or (it + 1) })

    @Test
    fun boxLayoutMatchesTheGameForKnownFaces() {
        val p = SkinLayout.Parts(SkinModel.CLASSIC)
        assertEquals(Rect(8, 8, 8, 8), p.head.front)      // face
        assertEquals(Rect(24, 8, 8, 8), p.head.back)
        assertEquals(Rect(40, 8, 8, 8), p.hat.front)
        assertEquals(Rect(20, 20, 8, 12), p.body.front)
        assertEquals(Rect(32, 20, 8, 12), p.body.back)
        assertEquals(Rect(44, 20, 4, 12), p.rightArm.front)
        assertEquals(Rect(4, 20, 4, 12), p.rightLeg.front)
        assertEquals(Rect(20, 52, 4, 12), p.leftLeg.front)
        assertEquals(Rect(36, 52, 4, 12), p.leftArm.front)
        assertEquals(Rect(20, 36, 8, 12), p.jacket.front)
        assertEquals(Rect(44, 36, 4, 12), p.rightSleeve.front)
        assertEquals(Rect(4, 36, 4, 12), p.rightPants.front)
        assertEquals(Rect(4, 52, 4, 12), p.leftPants.front)
        assertEquals(Rect(52, 52, 4, 12), p.leftSleeve.front)
    }

    @Test
    fun slimArmsAreThreePixelsWide() {
        val p = SkinLayout.Parts(SkinModel.SLIM)
        assertEquals(Rect(44, 20, 3, 12), p.rightArm.front)
        assertEquals(Rect(47, 20, 4, 12), p.rightArm.left)
        assertEquals(Rect(51, 20, 3, 12), p.rightArm.back)
        assertEquals(Rect(36, 52, 3, 12), p.leftArm.front)
    }

    @Test
    fun capeRegionsMatchTheGame() {
        assertEquals(Rect(1, 1, 10, 16), SkinLayout.capeOuter)
        assertEquals(Rect(12, 1, 10, 16), SkinLayout.capeInner)
    }

    @Test
    fun legacySkinUpgradeCopiesLikeTheGame() {
        // The game's own conversion (HttpTexture.processLegacySkin): rectangles copied, flipped, to the left limbs.
        val old = numbered(64, 32)
        val expected = Texture(64, 64)
        for (y in 0 until 32) for (x in 0 until 64) expected[x, y] = old[x, y]
        fun copyRect(x: Int, y: Int, xo: Int, yo: Int, w: Int, h: Int) {
            for (j in 0 until h) for (i in 0 until w) expected[x + xo + (w - 1 - i), y + yo + j] = old[x + i, y + j]
        }
        copyRect(4, 16, 16, 32, 4, 4); copyRect(8, 16, 16, 32, 4, 4)
        copyRect(0, 20, 24, 32, 4, 12); copyRect(4, 20, 16, 32, 4, 12); copyRect(8, 20, 8, 32, 4, 12); copyRect(12, 20, 16, 32, 4, 12)
        copyRect(44, 16, -8, 32, 4, 4); copyRect(48, 16, -8, 32, 4, 4)
        copyRect(40, 20, 0, 32, 4, 12); copyRect(44, 20, -8, 32, 4, 12); copyRect(48, 20, -16, 32, 4, 12); copyRect(52, 20, -8, 32, 4, 12)
        assertEquals(expected, SkinOps.upgradeLegacy(old))
    }

    @Test
    fun mirroringFlipsAndSwapsTheSides() {
        val t = numbered(64, 64)
        val p = SkinLayout.Parts(SkinModel.CLASSIC)
        val before = t.copy()
        SkinOps.copyRightToLeft(t, SkinModel.CLASSIC, outerLayer = false)
        // Front: flipped left to right.
        assertEquals(before[p.rightLeg.front.x + 3, p.rightLeg.front.y + 5], t[p.leftLeg.front.x, p.leftLeg.front.y + 5])
        // The right leg's outer side (its "right" face) becomes the left leg's outer side (its "left" face).
        assertEquals(before[p.rightLeg.right.x + 3, p.rightLeg.right.y], t[p.leftLeg.left.x, p.leftLeg.left.y])
        // The right leg itself is untouched.
        assertEquals(before[p.rightLeg.front.x, p.rightLeg.front.y], t[p.rightLeg.front.x, p.rightLeg.front.y])
    }

    @Test
    fun slimMirroringKeepsTheThreePixelFaces() {
        val t = numbered(64, 64)
        val p = SkinLayout.Parts(SkinModel.SLIM)
        val before = t.copy()
        SkinOps.copyRightToLeft(t, SkinModel.SLIM, outerLayer = false)
        assertEquals(before[p.rightArm.front.x + 2, p.rightArm.front.y + 1], t[p.leftArm.front.x, p.leftArm.front.y + 1])
        // The 4-pixel side faces come from the matching side faces (the arm's inner face goes to the other arm's inner face).
        assertEquals(before[p.rightArm.left.x + 3, p.rightArm.left.y + 2], t[p.leftArm.right.x, p.leftArm.right.y + 2])
    }

    @Test
    fun characterPreviewShowsTheRightFaceAndBlendsTheOuterLayer() {
        val skin = Texture(64, 64)
        val p = SkinLayout.Parts(SkinModel.CLASSIC)
        // Face red; hat layer half-transparent blue on its first pixel.
        for (y in 0 until 8) for (x in 0 until 8) skin[p.head.front.x + x, p.head.front.y + y] = 0xFFFF0000.toInt()
        skin[p.hat.front.x, p.hat.front.y] = 0x800000FF.toInt()
        val front = SkinRender.character(skin, SkinModel.CLASSIC, back = false)
        assertEquals(16, front.width); assertEquals(32, front.height)
        assertEquals(0xFFFF0000.toInt(), front[4 + 3, 3])
        val blended = front[4, 0]
        assertEquals(0xFF, blended ushr 24)
        assertTrue("blue mixed in", (blended and 0xFF) in 100..160 && (blended shr 16 and 0xFF) in 100..160)
        // The base layer ignores transparency: an empty skin is opaque black, not see-through.
        assertEquals(0xFF000000.toInt(), front[0, 8])
        // Back view reads the back faces.
        for (y in 0 until 8) for (x in 0 until 8) skin[p.head.back.x + x, p.head.back.y + y] = 0xFF00FF00.toInt()
        assertEquals(0xFF00FF00.toInt(), SkinRender.character(skin, SkinModel.CLASSIC, back = true)[4 + 3, 3])
    }

    @Test
    fun frontViewPutsTheRightArmOnTheViewersLeft() {
        val skin = Texture(64, 64)
        val p = SkinLayout.Parts(SkinModel.CLASSIC)
        for (y in 0 until 12) for (x in 0 until 4) {
            skin[p.rightArm.front.x + x, p.rightArm.front.y + y] = 0xFF112233.toInt()
            skin[p.leftArm.front.x + x, p.leftArm.front.y + y] = 0xFF445566.toInt()
        }
        val front = SkinRender.character(skin, SkinModel.CLASSIC, back = false)
        assertEquals(0xFF112233.toInt(), front[1, 10])
        assertEquals(0xFF445566.toInt(), front[14, 10])
        val back = SkinRender.character(skin, SkinModel.CLASSIC, back = true) // back faces are empty: black
        assertEquals(0xFF000000.toInt(), back[1, 10])
    }

    @Test
    fun capePreviewIsOpaqueOuterArea() {
        val cape = Texture(64, 32)
        cape[1, 1] = 0x00123456
        val outer = SkinRender.cape(cape, inner = false)
        assertEquals(10, outer.width); assertEquals(16, outer.height)
        assertEquals(0xFF123456.toInt(), outer[0, 0])
    }

    @Test
    fun capeHangsOverTheBackOfTheBodyOnly() {
        val skin = Texture(64, 64)
        val p = SkinLayout.Parts(SkinModel.CLASSIC)
        for (y in 0 until 12) for (x in 0 until 8) skin[p.body.back.x + x, p.body.back.y + y] = 0xFF00FF00.toInt()
        val cape = Texture(64, 32)
        for (y in 0 until 16) for (x in 0 until 10) cape[1 + x, 1 + y] = 0xFFFF0000.toInt()
        val back = SkinRender.character(skin, SkinModel.CLASSIC, back = true, cape = cape)
        // Cape is 10 wide from x = armWidth - 1, starting at the neck (y = 8), 16 tall.
        assertEquals(0xFFFF0000.toInt(), back[3, 8]); assertEquals(0xFFFF0000.toInt(), back[12, 23])
        assertEquals(0xFFFF0000.toInt(), back[8, 15])
        // From the front the cape is not visible.
        val front = SkinRender.character(skin, SkinModel.CLASSIC, back = false, cape = cape)
        assertEquals(SkinRender.character(skin, SkinModel.CLASSIC, back = false), front)
    }

    @Test
    fun transparentCapePixelsLetTheBodyShowThrough() {
        val skin = Texture(64, 64)
        val p = SkinLayout.Parts(SkinModel.CLASSIC)
        for (y in 0 until 12) for (x in 0 until 8) skin[p.body.back.x + x, p.body.back.y + y] = 0xFF00FF00.toInt()
        val back = SkinRender.character(skin, SkinModel.CLASSIC, back = true, cape = Texture(64, 32))
        assertEquals(0xFF00FF00.toInt(), back[8, 10])
    }

    @Test
    fun mannequinHasAFullBaseLayer() {
        val m = SkinRender.mannequin()
        val front = SkinRender.character(m, SkinModel.CLASSIC, back = false)
        // Head, body, arms and legs are all drawn (no black holes from empty skin areas).
        assertTrue(listOf(front[8, 3], front[8, 12], front[1, 10], front[6, 26]).none { it == 0xFF000000.toInt() })
    }

    @Test
    fun texturesCompareByContent() {
        assertEquals(numbered(4, 4), numbered(4, 4))
        assertNotEquals(numbered(4, 4), Texture(4, 4))
    }
}
