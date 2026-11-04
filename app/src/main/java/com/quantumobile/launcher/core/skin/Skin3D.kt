package com.quantumobile.launcher.core.skin

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A small software renderer that draws a Minecraft player (and a cape) from its textures, turning and walking.
 * Pure Kotlin on plain arrays, so it runs the same on a phone and in unit tests, and needs no OpenGL.
 *
 * Model space: 1 unit = 1 texture pixel, y up, the player faces +z, +x is the player's left, feet at y = 0.
 */
class Skin3DRenderer(val width: Int, val height: Int) {
    /** The picture: ARGB, transparent where nothing is drawn. Valid after [render]. */
    val pixels = IntArray(width * height)
    private val depth = FloatArray(width * height)

    /**
     * Draws the figure. [yaw] turns it around the vertical axis (0 = facing the viewer, radians), [pitch] tilts the
     * view (positive = from above), [walk] is the walking phase in radians and [swing] how far the limbs move (0 = standing).
     */
    fun render(skin: Texture, model: SkinModel, cape: Texture?, yaw: Float, pitch: Float, walk: Float, swing: Float) {
        require(SkinLayout.isSkinSize(skin.width, skin.height)) { "needs a 64×64 skin" }
        java.util.Arrays.fill(pixels, 0)
        java.util.Arrays.fill(depth, Float.MAX_VALUE)
        val camera = Camera(yaw, pitch, width, height)
        val parts = SkinLayout.Parts(model)
        val aw = model.armWidth.toFloat()

        val s = sin(walk) * swing * ARM_LEG_SWING
        val up = abs(sin(walk)) * swing * 0.5f // the whole body rises a little with each step

        // Each part: its base box, its outer-layer box, where it sits, and how it swings around its joint.
        fun limb(
            base: Box, outer: Box, x0: Float, w: Float, y0: Float, h: Float,
            pivotX: Float, pivotY: Float, angle: Float, inflate: Float = OUTER,
        ) {
            val t = Transform(pivotX, pivotY, 0f, angle, up)
            drawBox(camera, skin, base, x0, y0, -2f, x0 + w, y0 + h, 2f, t, outerLayer = false)
            val e = inflate
            drawBox(camera, skin, outer, x0 - e, y0 - e, -2f - e, x0 + w + e, y0 + h + e, 2f + e, t, outerLayer = true)
        }
        // head and body do not swing; the hat is the layer that stands out most
        limb(parts.head, parts.hat, -4f, 8f, 24f, 8f, 0f, 24f, 0f, inflate = HAT)
        limb(parts.body, parts.jacket, -4f, 8f, 12f, 12f, 0f, 12f, 0f)
        // arms hang from the shoulder: the model's left arm is on +x
        limb(parts.leftArm, parts.leftSleeve, 4f, aw, 12f, 12f, 4f + aw / 2f, 22f, -s)
        limb(parts.rightArm, parts.rightSleeve, -4f - aw, aw, 12f, 12f, -4f - aw / 2f, 22f, s)
        // legs hang from the hip; the right leg is forward when the left arm is
        limb(parts.leftLeg, parts.leftPants, 0f, 4f, 0f, 12f, 2f, 12f, s)
        limb(parts.rightLeg, parts.rightPants, -4f, 4f, 0f, 12f, -2f, 12f, -s)

        if (cape != null) drawCape(camera, cape, Transform(0f, 24f, 0f, CAPE_REST + swing * (0.10f + 0.12f * abs(sin(walk))), up))
    }

    // ---- scene pieces ----

    private fun drawCape(camera: Camera, cape: Texture, t: Transform) {
        val box = Box(0, 0, 10, 16, 1)
        val z0 = -2.5f; val z1 = -3.5f
        // The outer art faces backwards (-z) and the inner side faces the body, so their rectangles are swapped.
        val quads = listOf(
            Quad(Face3.BACK, box.front, -5f, 8f, z1, 5f, 24f, z0),
            Quad(Face3.FRONT, box.back, -5f, 8f, z1, 5f, 24f, z0),
            Quad(Face3.TOP, box.top, -5f, 8f, z1, 5f, 24f, z0),
            Quad(Face3.BOTTOM, box.bottom, -5f, 8f, z1, 5f, 24f, z0),
            Quad(Face3.RIGHT, box.right, -5f, 8f, z1, 5f, 24f, z0),
            Quad(Face3.LEFT, box.left, -5f, 8f, z1, 5f, 24f, z0),
        )
        for (q in quads) drawFace(camera, cape, q, t, outerLayer = false)
    }

    private fun drawBox(
        camera: Camera, tex: Texture, box: Box,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, t: Transform, outerLayer: Boolean,
    ) {
        drawFace(camera, tex, Quad(Face3.FRONT, box.front, x0, y0, z0, x1, y1, z1), t, outerLayer)
        drawFace(camera, tex, Quad(Face3.BACK, box.back, x0, y0, z0, x1, y1, z1), t, outerLayer)
        drawFace(camera, tex, Quad(Face3.RIGHT, box.right, x0, y0, z0, x1, y1, z1), t, outerLayer)
        drawFace(camera, tex, Quad(Face3.LEFT, box.left, x0, y0, z0, x1, y1, z1), t, outerLayer)
        drawFace(camera, tex, Quad(Face3.TOP, box.top, x0, y0, z0, x1, y1, z1), t, outerLayer)
        drawFace(camera, tex, Quad(Face3.BOTTOM, box.bottom, x0, y0, z0, x1, y1, z1), t, outerLayer)
    }

    private enum class Face3(val nx: Float, val ny: Float, val nz: Float) {
        FRONT(0f, 0f, 1f), BACK(0f, 0f, -1f), RIGHT(-1f, 0f, 0f), LEFT(1f, 0f, 0f), TOP(0f, 1f, 0f), BOTTOM(0f, -1f, 0f)
    }

    /** One face of a cuboid: which side, which texture rectangle, and the cuboid's corners (x0,y0,z0)–(x1,y1,z1). */
    private class Quad(val face: Face3, val rect: Rect, val x0: Float, val y0: Float, val z0: Float, val x1: Float, val y1: Float, val z1: Float)

    /** A rotation about the x axis (limb swing) around the point (px, py, pz), then a lift of [dy] (the walking bob). */
    private class Transform(val px: Float, val py: Float, val pz: Float, val angle: Float, val dy: Float) {
        private val c = cos(angle); private val s = sin(angle)
        fun x(x: Float, y: Float, z: Float) = x
        fun y(x: Float, y: Float, z: Float) = py + (y - py) * c - (z - pz) * s + dy
        fun z(x: Float, y: Float, z: Float) = pz + (y - py) * s + (z - pz) * c
        fun nx(nx: Float, ny: Float, nz: Float) = nx
        fun ny(nx: Float, ny: Float, nz: Float) = ny * c - nz * s
        fun nz(nx: Float, ny: Float, nz: Float) = ny * s + nz * c
    }

    /** Turns the scene around its centre and looks at it from in front, with perspective. */
    private class Camera(yaw: Float, pitch: Float, val w: Int, val h: Int) {
        private val cy = cos(yaw); private val sy = sin(yaw)
        private val cp = cos(pitch); private val sp = sin(pitch)
        private val focal = h * 0.80f * DISTANCE / 36f

        /** View-space x (right), y (up), z (towards the camera) of a model point. */
        fun viewX(x: Float, y: Float, z: Float) = x * cy + z * sy
        private fun rotatedZ(x: Float, z: Float) = -x * sy + z * cy
        fun viewY(x: Float, y: Float, z: Float) = (y - CENTER_Y) * cp - rotatedZ(x, z) * sp
        fun viewZ(x: Float, y: Float, z: Float) = (y - CENTER_Y) * sp + rotatedZ(x, z) * cp
        fun screenX(vx: Float, vz: Float) = w / 2f + focal * vx / (DISTANCE - vz)
        fun screenY(vy: Float, vz: Float) = h / 2f - focal * vy / (DISTANCE - vz)
        fun depth(vz: Float) = DISTANCE - vz
    }

    private fun drawFace(camera: Camera, tex: Texture, q: Quad, t: Transform, outerLayer: Boolean) {
        // Corners in order: left-bottom, right-bottom, right-top, left-top as the face is seen from outside.
        val corners: FloatArray = when (q.face) {
            Face3.FRONT -> floatArrayOf(q.x0, q.y0, q.z1, q.x1, q.y0, q.z1, q.x1, q.y1, q.z1, q.x0, q.y1, q.z1)
            Face3.BACK -> floatArrayOf(q.x1, q.y0, q.z0, q.x0, q.y0, q.z0, q.x0, q.y1, q.z0, q.x1, q.y1, q.z0)
            Face3.RIGHT -> floatArrayOf(q.x0, q.y0, q.z0, q.x0, q.y0, q.z1, q.x0, q.y1, q.z1, q.x0, q.y1, q.z0)
            Face3.LEFT -> floatArrayOf(q.x1, q.y0, q.z1, q.x1, q.y0, q.z0, q.x1, q.y1, q.z0, q.x1, q.y1, q.z1)
            Face3.TOP -> floatArrayOf(q.x0, q.y1, q.z0, q.x1, q.y1, q.z0, q.x1, q.y1, q.z1, q.x0, q.y1, q.z1)
            Face3.BOTTOM -> floatArrayOf(q.x0, q.y0, q.z1, q.x1, q.y0, q.z1, q.x1, q.y0, q.z0, q.x0, q.y0, q.z0)
        }
        // Texture coordinates of those corners. Top and bottom run along x (u) and z (v: towards the front on top,
        // towards the back underneath); the sides run along their width with v going down.
        val r = q.rect
        val u0 = r.x.toFloat(); val u1 = (r.x + r.w).toFloat()
        val v0 = r.y.toFloat(); val v1 = (r.y + r.h).toFloat()
        val uv: FloatArray = when (q.face) {
            Face3.TOP -> floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1)
            Face3.BOTTOM -> floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1)
            else -> floatArrayOf(u0, v1, u1, v1, u1, v0, u0, v0)
        }

        // To view space, with the limb swing applied first.
        val vx = FloatArray(4); val vy = FloatArray(4); val vz = FloatArray(4)
        for (i in 0 until 4) {
            val x = corners[i * 3]; val y = corners[i * 3 + 1]; val z = corners[i * 3 + 2]
            val ty = t.y(x, y, z); val tz = t.z(x, y, z)
            vx[i] = camera.viewX(x, ty, tz); vy[i] = camera.viewY(x, ty, tz); vz[i] = camera.viewZ(x, ty, tz)
        }
        // Outward normal in view space; the face is hidden when it points away from the camera.
        val nx = t.nx(q.face.nx, q.face.ny, q.face.nz); val ny = t.ny(q.face.nx, q.face.ny, q.face.nz); val nz = t.nz(q.face.nx, q.face.ny, q.face.nz)
        val wnx = camera.viewX(nx, ny, nz)
        val wny = camera.viewY(nx, ny + CENTER_Y, nz) // direction only: undo the centre offset used for points
        val wnz = camera.viewZ(nx, ny + CENTER_Y, nz)
        val toCamX = -vx[0]; val toCamY = -vy[0]; val toCamZ = DISTANCE - vz[0]
        if (wnx * toCamX + wny * toCamY + wnz * toCamZ <= 0f) return
        val light = lighting(wnx, wny, wnz)

        val sx = FloatArray(4); val sy = FloatArray(4); val inv = FloatArray(4)
        for (i in 0 until 4) {
            sx[i] = camera.screenX(vx[i], vz[i]); sy[i] = camera.screenY(vy[i], vz[i]); inv[i] = 1f / camera.depth(vz[i])
        }
        // Two triangles: (0,1,2) and (0,2,3).
        triangle(tex, r, outerLayer, light, sx, sy, inv, uv, 0, 1, 2)
        triangle(tex, r, outerLayer, light, sx, sy, inv, uv, 0, 2, 3)
    }

    private fun lighting(nx: Float, ny: Float, nz: Float): Float {
        // A light above and a little to the right of the camera; faces turned away keep some ambient light.
        val d = nx * -0.25f + ny * 0.70f + nz * 0.66f
        return 0.62f + 0.38f * max(0f, d)
    }

    private fun edge(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float) = (px - ax) * (by - ay) - (py - ay) * (bx - ax)

    private fun triangle(
        tex: Texture, rect: Rect, outerLayer: Boolean, light: Float,
        sx: FloatArray, sy: FloatArray, inv: FloatArray, uv: FloatArray, a: Int, b: Int, c: Int,
    ) {
        val area = edge(sx[a], sy[a], sx[b], sy[b], sx[c], sy[c])
        if (area == 0f) return
        val minX = max(0, floor(min(sx[a], min(sx[b], sx[c]))).toInt())
        val maxX = min(width - 1, floor(max(sx[a], max(sx[b], sx[c]))).toInt())
        val minY = max(0, floor(min(sy[a], min(sy[b], sy[c]))).toInt())
        val maxY = min(height - 1, floor(max(sy[a], max(sy[b], sy[c]))).toInt())
        val ua = uv[a * 2] * inv[a]; val va = uv[a * 2 + 1] * inv[a]
        val ub = uv[b * 2] * inv[b]; val vb = uv[b * 2 + 1] * inv[b]
        val uc = uv[c * 2] * inv[c]; val vc = uv[c * 2 + 1] * inv[c]
        val maxTx = rect.x + rect.w - 1; val maxTy = rect.y + rect.h - 1
        for (y in minY..maxY) {
            val py = y + 0.5f
            for (x in minX..maxX) {
                val px = x + 0.5f
                val w0 = edge(sx[b], sy[b], sx[c], sy[c], px, py) / area
                val w1 = edge(sx[c], sy[c], sx[a], sy[a], px, py) / area
                val w2 = edge(sx[a], sy[a], sx[b], sy[b], px, py) / area
                if (w0 < -EPS || w1 < -EPS || w2 < -EPS) continue
                val invD = w0 * inv[a] + w1 * inv[b] + w2 * inv[c]
                if (invD <= 0f) continue
                val d = 1f / invD
                val i = y * width + x
                if (d >= depth[i]) continue
                val u = (w0 * ua + w1 * ub + w2 * uc) / invD
                val v = (w0 * va + w1 * vb + w2 * vc) / invD
                val tx = min(max(floor(u).toInt(), rect.x), maxTx)
                val ty = min(max(floor(v).toInt(), rect.y), maxTy)
                val argb = tex[tx, ty]
                // The outer layer is see-through where it is transparent; the base layer ignores transparency.
                if (outerLayer && (argb ushr 24) < 128) continue
                depth[i] = d
                pixels[i] = shade(argb, light)
            }
        }
    }

    private fun shade(argb: Int, light: Float): Int {
        val r = ((argb shr 16 and 0xFF) * light).toInt().coerceIn(0, 255)
        val g = ((argb shr 8 and 0xFF) * light).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * light).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        /** How far the view is from the figure's centre, in model units. */
        const val DISTANCE = 80f
        private const val CENTER_Y = 16f
        /** The outer layers are slightly bigger than the base layers, as in the game (the hat more than the rest). */
        private const val OUTER = 0.25f
        private const val HAT = 0.5f
        private const val EPS = 1e-4f
        /** How far arms and legs swing at the most (radians). */
        private const val ARM_LEG_SWING = 0.75f
        /** The cape's resting backward tilt (radians). */
        private const val CAPE_REST = 0.06f

        /** Turns a walking time (seconds) into the swing phase (radians): about 1.4 steps per second. */
        fun walkPhase(seconds: Float) = (seconds * 2f * PI.toFloat() * 1.1f)
    }
}
