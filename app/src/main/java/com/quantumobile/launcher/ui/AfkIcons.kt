package com.quantumobile.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.afk.AfkServer
import java.io.File

/**
 * Icons for AFK servers: built-in pixel-art presets, the server's own list icon (saved when the bot pings it),
 * or an image the user picked (cropped to a square, saved in app storage).
 */
object AfkIcons {
    class Preset(val key: String, @DrawableRes val res: Int, @StringRes val label: Int)

    val presets = listOf(
        Preset("grass", R.drawable.afk_icon_grass, R.string.afk_icon_grass),
        Preset("stone", R.drawable.afk_icon_stone, R.string.afk_icon_stone),
        Preset("diamond", R.drawable.afk_icon_diamond, R.string.afk_icon_diamond),
        Preset("sword", R.drawable.afk_icon_sword, R.string.afk_icon_sword),
        Preset("pickaxe", R.drawable.afk_icon_pickaxe, R.string.afk_icon_pickaxe),
        Preset("heart", R.drawable.afk_icon_heart, R.string.afk_icon_heart),
        Preset("apple", R.drawable.afk_icon_apple, R.string.afk_icon_apple),
        Preset("tree", R.drawable.afk_icon_tree, R.string.afk_icon_tree),
        Preset("house", R.drawable.afk_icon_house, R.string.afk_icon_house),
        Preset("fire", R.drawable.afk_icon_fire, R.string.afk_icon_fire),
        Preset("chest", R.drawable.afk_icon_chest, R.string.afk_icon_chest),
        Preset("moon", R.drawable.afk_icon_moon, R.string.afk_icon_moon),
    )

    /** Shown for "server icon" until the server has been reached once (or when it has no icon). */
    val fallback get() = presets.first()

    private fun dir(context: Context) = File(context.filesDir, "afk-icons").apply { mkdirs() }
    fun serverFile(context: Context, id: String) = File(dir(context), "$id-server.png")
    fun customFile(context: Context, id: String) = File(dir(context), "$id.png")
    fun pendingFile(context: Context, id: String) = File(dir(context), "$id.pending.png")

    fun preset(key: String?) = presets.firstOrNull { "${AfkServer.ICON_PRESET}${it.key}" == key }

    /** The file behind [icon] for server [id], if it is a stored image that exists. */
    fun file(context: Context, id: String, icon: String?): File? = when (icon) {
        null -> serverFile(context, id)
        AfkServer.ICON_CUSTOM -> customFile(context, id)
        else -> null
    }?.takeIf { it.isFile }

    /** Drawable for [icon] of server [id]; pixel images are scaled without smoothing so they stay crisp. */
    fun drawable(context: Context, id: String, icon: String?): Drawable {
        file(context, id, icon)?.let { f -> BitmapFactory.decodeFile(f.path)?.let { return pixelated(context, it) } }
        val res = (preset(icon) ?: fallback).res
        return ContextCompat.getDrawable(context, res)!!
    }

    fun pixelated(context: Context, bitmap: Bitmap) = BitmapDrawable(context.resources, bitmap).apply {
        isFilterBitmap = bitmap.width > 96 // keep small pixel-art icons sharp, smooth photos
        setAntiAlias(false)
    }

    fun saveServerIcon(context: Context, id: String, png: ByteArray) {
        if (BitmapFactory.decodeByteArray(png, 0, png.size) == null) return // not an image: keep the old one
        val f = serverFile(context, id)
        if (f.isFile && f.readBytes().contentEquals(png)) return
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(png)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Decodes a picked image, crops it to the centre square, scales it to 128 px and saves it as [out]. */
    fun importImage(context: Context, uri: Uri, out: File): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        val src = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)

    fun deleteAll(context: Context, id: String) {
        listOf(serverFile(context, id), customFile(context, id), pendingFile(context, id)).forEach { it.delete() }
    }

    private const val SIZE = 128
}
