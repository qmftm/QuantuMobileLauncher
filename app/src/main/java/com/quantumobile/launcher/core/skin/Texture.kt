package com.quantumobile.launcher.core.skin

/** A picture as non-premultiplied ARGB ints (the same layout as `Bitmap.getPixels`), row by row. */
class Texture(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init { require(pixels.size == width * height) { "pixels do not match $width×$height" } }

    fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height
    operator fun get(x: Int, y: Int) = pixels[y * width + x]
    operator fun set(x: Int, y: Int, argb: Int) { pixels[y * width + x] = argb }
    fun copy() = Texture(width, height, pixels.copyOf())

    override fun equals(other: Any?) = other is Texture && width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    override fun hashCode() = 31 * (31 * width + height) + pixels.contentHashCode()
}

/** What a texture is for. */
enum class SkinKind { SKIN, CAPE }

/** The arm width of the character model: Steve ("classic", 4 px) or Alex ("slim", 3 px). */
enum class SkinModel(val armWidth: Int, val apiName: String) {
    CLASSIC(4, "classic"), SLIM(3, "slim");

    companion object {
        fun of(slim: Boolean) = if (slim) SLIM else CLASSIC
    }
}

/** A rectangle on a texture. */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun contains(px: Int, py: Int) = px >= x && px < x + w && py >= y && py < y + h
}

/**
 * A box in the model with its UV origin at ([u], [v]): the six faces of a cuboid [w]×[h]×[d] are laid out the way
 * Minecraft's model code lays them out, so every body part follows from four numbers.
 */
data class Box(val u: Int, val v: Int, val w: Int, val h: Int, val d: Int) {
    val top get() = Rect(u + d, v, w, d)
    val bottom get() = Rect(u + d + w, v, w, d)
    /** The face on the model's right side (seen from the viewer when looking at the model's back, on the left). */
    val right get() = Rect(u, v + d, d, h)
    val front get() = Rect(u + d, v + d, w, h)
    val left get() = Rect(u + d + w, v + d, d, h)
    val back get() = Rect(u + d + w + d, v + d, w, h)
}

object SkinLayout {
    const val SKIN_SIZE = 64
    const val CAPE_WIDTH = 64
    const val CAPE_HEIGHT = 32

    fun isSkinSize(w: Int, h: Int) = w == 64 && h == 64
    fun isLegacySkinSize(w: Int, h: Int) = w == 64 && h == 32
    fun isCapeSize(w: Int, h: Int) = w == CAPE_WIDTH && h == CAPE_HEIGHT

    /** The parts of a 64×64 skin; [model] sets the arm width. Each has a base layer and an outer layer. */
    class Parts(model: SkinModel) {
        val aw = model.armWidth
        val head = Box(0, 0, 8, 8, 8)
        val hat = Box(32, 0, 8, 8, 8)
        val body = Box(16, 16, 8, 12, 4)
        val jacket = Box(16, 32, 8, 12, 4)
        val rightArm = Box(40, 16, aw, 12, 4)
        val rightSleeve = Box(40, 32, aw, 12, 4)
        val leftArm = Box(32, 48, aw, 12, 4)
        val leftSleeve = Box(48, 48, aw, 12, 4)
        val rightLeg = Box(0, 16, 4, 12, 4)
        val rightPants = Box(0, 32, 4, 12, 4)
        val leftLeg = Box(16, 48, 4, 12, 4)
        val leftPants = Box(0, 48, 4, 12, 4)
    }

    /** Where the cape's artwork is: [outer] is what shows on the player's back, [inner] the side against the body. */
    val capeOuter = Rect(1, 1, 10, 16)
    val capeInner = Rect(12, 1, 10, 16)
    private val capeBox = Box(0, 0, 10, 16, 1)
}

object SkinOps {
    /** Moves an old 64×32 skin onto the 64×64 layout, copying the right arm and leg to the left (as the game does). */
    fun upgradeLegacy(old: Texture): Texture {
        require(SkinLayout.isLegacySkinSize(old.width, old.height)) { "not a 64×32 skin" }
        val out = Texture(64, 64)
        for (y in 0 until 32) for (x in 0 until 64) out[x, y] = old[x, y]
        copyRightToLeft(out, SkinModel.CLASSIC, outerLayer = false)
        return out
    }

    /**
     * Mirrors the right arm and leg onto the left ones ([outerLayer]: the sleeve and trouser layer instead of the base
     * layer). Each face of the left limb is the flipped matching face of the right limb, so left and right swap sides.
     */
    fun copyRightToLeft(tex: Texture, model: SkinModel, outerLayer: Boolean) {
        val p = SkinLayout.Parts(model)
        fun mirror(src: Box, dst: Box) {
            copyFlipped(tex, src.top, dst.top)
            copyFlipped(tex, src.bottom, dst.bottom)
            copyFlipped(tex, src.front, dst.front)
            copyFlipped(tex, src.back, dst.back)
            copyFlipped(tex, src.left, dst.right)  // the right limb's inner face becomes the left limb's inner face
            copyFlipped(tex, src.right, dst.left)
        }
        if (outerLayer) {
            mirror(p.rightSleeve, p.leftSleeve); mirror(p.rightPants, p.leftPants)
        } else {
            mirror(p.rightArm, p.leftArm); mirror(p.rightLeg, p.leftLeg)
        }
    }

    private fun copyFlipped(tex: Texture, src: Rect, dst: Rect) {
        if (src.w != dst.w || src.h != dst.h) return // e.g. an arm face that does not exist in this model
        val copy = Array(src.h) { y -> IntArray(src.w) { x -> tex[src.x + x, src.y + y] } }
        for (y in 0 until src.h) for (x in 0 until src.w) tex[dst.x + x, dst.y + y] = copy[y][src.w - 1 - x]
    }
}

/** Draws what the game would show, to preview the work. */
object SkinRender {
    /** Layer colour as the game draws it: the base layer ignores transparency, the outer layer blends. */
    private fun over(base: Int, top: Int): Int {
        val a = top ushr 24
        if (a == 0) return base
        if (a == 255) return top
        fun mix(shift: Int): Int {
            val b = base ushr shift and 0xFF; val t = top ushr shift and 0xFF
            return (t * a + b * (255 - a)) / 255
        }
        return 0xFF shl 24 or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun opaque(argb: Int) = argb or (0xFF shl 24)

    /**
     * Front ([back] false) or back view of a 64×64 skin as a small picture: 8+2 arms wide, 32 tall, on transparent.
     * [cape] (a 64×32 cape texture) is drawn on the back view, hanging from the shoulders.
     */
    fun character(skin: Texture, model: SkinModel, back: Boolean, cape: Texture? = null): Texture {
        require(SkinLayout.isSkinSize(skin.width, skin.height)) { "needs a 64×64 skin" }
        val p = SkinLayout.Parts(model)
        val aw = p.aw
        val out = Texture(8 + 2 * aw, 32)
        fun face(b: Box) = if (back) b.back else b.front

        // Paints one body part: its base-layer face, then the outer-layer face over it.
        fun draw(base: Box, outer: Box, dx: Int, dy: Int) {
            val bf = face(base); val of = face(outer)
            for (y in 0 until bf.h) for (x in 0 until bf.w) {
                val under = opaque(skin[bf.x + x, bf.y + y])
                out[dx + x, dy + y] = over(under, skin[of.x + x, of.y + y])
            }
        }
        // Seen from the front, the model's right limbs are on the viewer's left; from behind they swap.
        val leftSideIsRight = !back
        val armL = if (leftSideIsRight) p.rightArm to p.rightSleeve else p.leftArm to p.leftSleeve
        val armR = if (leftSideIsRight) p.leftArm to p.leftSleeve else p.rightArm to p.rightSleeve
        val legL = if (leftSideIsRight) p.rightLeg to p.rightPants else p.leftLeg to p.leftPants
        val legR = if (leftSideIsRight) p.leftLeg to p.leftPants else p.rightLeg to p.rightPants
        draw(p.head, p.hat, aw, 0)
        draw(p.body, p.jacket, aw, 8)
        draw(armL.first, armL.second, 0, 8)
        draw(armR.first, armR.second, aw + 8, 8)
        draw(legL.first, legL.second, aw, 20)
        draw(legR.first, legR.second, aw + 4, 20)
        if (back && cape != null) {
            // The cape is 10 wide, one pixel wider than the body on each side, and hangs from the neck.
            val c = SkinLayout.capeOuter
            for (y in 0 until c.h) for (x in 0 until c.w) {
                val dx = aw - 1 + x; val dy = 8 + y
                if (dx in 0 until out.width && dy in 0 until out.height) out[dx, dy] = over(out[dx, dy], cape[c.x + x, c.y + y])
            }
        }
        return out
    }

    /** A plain grey figure, to show a cape on when no skin is chosen. */
    fun mannequin(): Texture {
        val t = Texture(SkinLayout.SKIN_SIZE, SkinLayout.SKIN_SIZE)
        val p = SkinLayout.Parts(SkinModel.CLASSIC)
        fun paint(b: Box, argb: Int) {
            for (r in listOf(b.top, b.bottom, b.right, b.front, b.left, b.back))
                for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) t[x, y] = argb
        }
        paint(p.head, 0xFFB5B5B5.toInt()); paint(p.body, 0xFF8E8E8E.toInt())
        paint(p.rightArm, 0xFF9C9C9C.toInt()); paint(p.leftArm, 0xFF9C9C9C.toInt())
        paint(p.rightLeg, 0xFF7A7A7A.toInt()); paint(p.leftLeg, 0xFF7A7A7A.toInt())
        return t
    }

    /** The cape as seen from behind ([inner] false) or from the body's side. */
    fun cape(cape: Texture, inner: Boolean): Texture {
        require(SkinLayout.isCapeSize(cape.width, cape.height)) { "needs a 64×32 cape" }
        val r = if (inner) SkinLayout.capeInner else SkinLayout.capeOuter
        val out = Texture(r.w, r.h)
        for (y in 0 until r.h) for (x in 0 until r.w) out[x, y] = opaque(cape[r.x + x, r.y + y])
        return out
    }
}
