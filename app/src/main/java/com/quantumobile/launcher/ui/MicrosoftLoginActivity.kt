package com.quantumobile.launcher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.quantumobile.launcher.core.auth.MicrosoftAuth

/** Shows the Microsoft sign-in page and returns the OAuth authorization code. */
class MicrosoftLoginActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        setContentView(web)
        // Start from a clean session so a different account can be added.
        CookieManager.getInstance().removeAllCookies(null)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = handle(request.url)
        }
        web.loadUrl(MicrosoftAuth.authorizeUrl)
    }

    private fun handle(uri: Uri): Boolean {
        if (!uri.toString().startsWith(MicrosoftAuth.REDIRECT_URI)) return false
        val code = uri.getQueryParameter("code")
        if (code != null) setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
        else setResult(Activity.RESULT_CANCELED)
        finish()
        return true
    }

    companion object {
        const val EXTRA_CODE = "code"
    }
}
