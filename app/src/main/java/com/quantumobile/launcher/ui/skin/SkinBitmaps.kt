package com.quantumobile.launcher.ui.skin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.quantumobile.launcher.core.skin.SkinKind
import com.quantumobile.launcher.core.skin.SkinModel
import com.quantumobile.launcher.core.skin.SkinRender
import com.quantumobile.launcher.core.skin.Texture
import java.io.ByteArrayOutputStream

/** Conversions between [Texture] and Android bitmaps / PNG files. */
object SkinBitmaps {
    /** Reads a PNG (or any image) keeping the colours of transparent pixels, which skins can rely on. */
    fun decode(bytes: ByteArray): Texture? {
        val options = BitmapFactory.Options().apply { inPremultiplied = false; inScaled = false }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (bmp.width > MAX_SIDE || bmp.height > MAX_SIDE) return null
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return Texture(bmp.width, bmp.height, pixels)
    }

    /** A bitmap for drawing on screen. */
    fun toBitmap(t: Texture): Bitmap =
        Bitmap.createBitmap(t.pixels, t.width, t.height, Bitmap.Config.ARGB_8888)

    /** PNG file contents; colours under fully transparent pixels are kept, as the game keeps them. */
    fun encodePng(t: Texture): ByteArray {
        val bmp = Bitmap.createBitmap(t.width, t.height, Bitmap.Config.ARGB_8888)
        bmp.isPremultiplied = false
        bmp.setPixels(t.pixels, 0, t.width, 0, 0, t.width, t.height)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** A small picture of the work for lists: the character from the front, or the cape from behind. */
    fun thumbPng(t: Texture, kind: SkinKind, model: SkinModel): ByteArray {
        val small = if (kind == SkinKind.CAPE) SkinRender.cape(t, inner = false) else SkinRender.character(t, model, back = false)
        return encodePng(scaled(small, 8))
    }

    /** Nearest-neighbour enlargement by a whole factor. */
    fun scaled(t: Texture, k: Int): Texture {
        val out = Texture(t.width * k, t.height * k)
        for (y in 0 until out.height) for (x in 0 until out.width) out[x, y] = t[x / k, y / k]
        return out
    }

    /** Largest picture accepted when importing (HD skins are rejected later, by size). */
    private const val MAX_SIDE = 4096
}
