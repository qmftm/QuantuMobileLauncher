package com.quantumobile.launcher.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import androidx.lifecycle.LifecycleCoroutineScope
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** Loads small remote images (skin heads, mod icons) with a memory and disk cache. */
object RemoteImage {
    private val memory = LruCache<String, Bitmap>(64)

    fun load(view: ImageView, url: String?, scope: LifecycleCoroutineScope, placeholder: Int, maxAgeHours: Long = 24) {
        view.tag = url
        view.setImageResource(placeholder)
        if (url == null) return
        memory.get(url)?.let { view.setImageBitmap(it); return }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetch(url, maxAgeHours) } ?: return@launch
            memory.put(url, bitmap)
            if (view.tag == url) view.setImageBitmap(bitmap)
        }
    }

    private fun fetch(url: String, maxAgeHours: Long): Bitmap? = runCatching {
        val dir = File(QuantumApp.instance.cacheDir, "images").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(dir, name)
        if (!file.isFile || System.currentTimeMillis() - file.lastModified() > maxAgeHours * 3600_000L) {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (res.isSuccessful) res.body?.bytes()?.let { file.writeBytes(it) }
            }
        }
        BitmapFactory.decodeFile(file.path)
    }.getOrNull()
}
