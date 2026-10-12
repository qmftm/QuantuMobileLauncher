package com.quantumobile.launcher.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import androidx.lifecycle.LifecycleCoroutineScope
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.auth.AccountType
import com.quantumobile.launcher.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Loads Minecraft skin heads (mc-heads.net) with memory and disk caching. */
object AvatarLoader {
    private val memory = LruCache<String, Bitmap>(32)

    fun load(view: ImageView, account: Account?, scope: LifecycleCoroutineScope) {
        val key = when {
            account == null -> null
            account.type == AccountType.MICROSOFT -> account.uuid
            else -> "MHF_Steve" // offline players have no skin of their own
        }
        view.tag = key
        view.setImageResource(R.drawable.ic_person)
        if (key == null) return
        memory.get(key)?.let { view.setImageBitmap(it); return }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetch(key) } ?: return@launch
            memory.put(key, bitmap)
            if (view.tag == key) view.setImageBitmap(bitmap)
        }
    }

    private fun fetch(key: String): Bitmap? = runCatching {
        val dir = File(QuantumApp.instance.cacheDir, "avatars").apply { mkdirs() }
        val file = File(dir, "$key.png")
        if (!file.isFile || System.currentTimeMillis() - file.lastModified() > 24 * 3600_000L) {
            Http.client.newCall(Request.Builder().url("https://mc-heads.net/avatar/$key/128").build()).execute().use { res ->
                if (res.isSuccessful) res.body?.bytes()?.let { file.writeBytes(it) }
            }
        }
        BitmapFactory.decodeFile(file.path)
    }.getOrNull()
}
