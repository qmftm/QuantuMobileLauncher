package org.lwjgl.glfw

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.Keep
import com.quantumobile.launcher.QuantumApp
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android half of the PojavLauncher native bridge (libpojavexec).
 *
 * The package, class and member names are dictated by the native code: `JNI_OnLoad` looks this class up as
 * `org/lwjgl/glfw/CallbackBridge`, registers the `nativeSend*` methods on it and calls back into the static
 * `accessAndroidClipboard`, `onGrabStateChanged` and `onDirectInputEnable` methods. The JVM that runs Minecraft has its own
 * class with the same name (from the LWJGL jar); the two talk through shared native state.
 */
@Keep
object CallbackBridge {
    const val CLIPBOARD_COPY = 2000
    const val CLIPBOARD_PASTE = 2001
    const val CLIPBOARD_OPEN = 2002

    /** Size of the game's framebuffer in pixels; input coordinates are expressed in this space. */
    @Volatile @JvmField var windowWidth = 0
    @Volatile @JvmField var windowHeight = 0

    /** True while Minecraft has hidden and captured the cursor (in game, as opposed to menus). */
    @Volatile @JvmField var isGrabbing = false

    private val grabListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    init {
        // JNI_OnLoad (first load = this Android VM) stores the VM and registers the methods below.
        System.loadLibrary("pojavexec")
    }

    /** Touching this object loads the native library; call it before [net.kdt.pojavlaunch.utils.JREUtils]. */
    @JvmStatic fun ensureLoaded() = Unit

    // ---- input, called from the UI ----

    fun sendKey(key: Int, scancode: Int, action: Int, mods: Int) = nativeSendKey(key, scancode, action, mods)

    /** Sends a typed character; characters outside the BMP go out as a surrogate pair. */
    fun sendChar(codepoint: Int, mods: Int) {
        for (c in Character.toChars(codepoint)) {
            nativeSendCharMods(c, mods)
            nativeSendChar(c)
        }
    }

    fun sendCursorPos(x: Float, y: Float) = nativeSendCursorPos(x, y)
    fun sendMouseButton(button: Int, action: Int, mods: Int) = nativeSendMouseButton(button, action, mods)
    fun sendScroll(dx: Double, dy: Double) = nativeSendScroll(dx, dy)

    fun sendScreenSize(width: Int, height: Int) {
        windowWidth = width
        windowHeight = height
        nativeSendScreenSize(width, height)
    }

    fun addGrabListener(listener: (Boolean) -> Unit) {
        listener(isGrabbing)
        grabListeners.add(listener)
    }

    fun removeGrabListener(listener: (Boolean) -> Unit) {
        grabListeners.remove(listener)
    }

    // ---- called from the JVM side through libpojavexec ----

    @JvmStatic @Keep
    @Suppress("unused")
    private fun accessAndroidClipboard(type: Int, copy: String?): String? {
        val context = QuantumApp.instance
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        return when (type) {
            CLIPBOARD_COPY -> { clipboard.setPrimaryClip(ClipData.newPlainText("Copy", copy.orEmpty())); null }
            CLIPBOARD_PASTE ->
                if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true)
                    clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                else ""
            CLIPBOARD_OPEN -> { openLink(context, copy); null }
            else -> null
        }
    }

    private fun openLink(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim('\u0000'))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @JvmStatic @Keep
    @Suppress("unused")
    private fun onGrabStateChanged(grabbing: Boolean) {
        isGrabbing = grabbing
        grabListeners.forEach { it(grabbing) }
    }

    @JvmStatic @Keep
    @Suppress("unused")
    private fun onDirectInputEnable() = Unit // gamepad direct input is not used by this launcher

    // ---- natives (registered by JNI_OnLoad / resolved by name) ----

    @JvmStatic @Keep external fun nativeSetUseInputStackQueue(useInputStackQueue: Boolean)
    @JvmStatic @Keep private external fun nativeSendChar(codepoint: Char): Boolean
    @JvmStatic @Keep private external fun nativeSendCharMods(codepoint: Char, mods: Int): Boolean
    @JvmStatic @Keep private external fun nativeSendKey(key: Int, scancode: Int, action: Int, mods: Int)
    @JvmStatic @Keep private external fun nativeSendCursorPos(x: Float, y: Float)
    @JvmStatic @Keep private external fun nativeSendMouseButton(button: Int, action: Int, mods: Int)
    @JvmStatic @Keep private external fun nativeSendScroll(xoffset: Double, yoffset: Double)
    @JvmStatic @Keep private external fun nativeSendScreenSize(width: Int, height: Int)
    @JvmStatic @Keep external fun nativeSetWindowAttrib(attrib: Int, value: Int)
}
