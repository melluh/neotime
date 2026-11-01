package com.melluh.neotime.auth

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.melluh.neotime.timeedit.OsirisClient
import com.melluh.neotime.timeedit.OsirisToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gets an OSIRIS token without a login of its own: OSIRIS signs in against the same Microsoft
 * tenant as TimeEdit, so the cookies in the WebView carry it through in an offscreen WebView.
 * Deliberately separate from [SessionManager]: failing here must not affect the schedule. The
 * token is only kept in memory.
 */
class OsirisSession(private val context: Context, private val client: OsirisClient) {
    private val mutex = Mutex()

    @Volatile
    private var token: OsirisToken? = null

    /** A usable token, or null when signing in would need the user. */
    suspend fun token(): OsirisToken? = mutex.withLock {
        token?.takeUnless { it.isExpired() }?.let { return it }
        val code = runOffscreenSignIn(client.loginUrl()) ?: return null
        client.exchangeCode(code).also { token = it }
    }

    fun invalidate() {
        token = null
    }

    private suspend fun runOffscreenSignIn(loginUrl: String): String? = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>()
        val handler = Handler(Looper.getMainLooper())
        val waitingForInput = Runnable { result.complete(null) }

        /** OSIRIS's page would redeem the single-use code itself, so it must not get to run. */
        fun finishOnCallback(url: String?): Boolean {
            if (!client.isCallback(url)) return false
            result.complete(client.callbackCode(url))
            return true
        }

        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && finishOnCallback(request.url.toString())

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    handler.removeCallbacks(waitingForInput)
                    if (finishOnCallback(url)) view.stopLoading()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (TimeEditLogin.isIdentityProvider(url)) handler.postDelayed(waitingForInput, IDP_SETTLE_MS)
                }
            }
        }
        try {
            webView.loadUrl(loginUrl)
            withTimeoutOrNull(TIMEOUT_MS) { result.await() }
        } finally {
            handler.removeCallbacks(waitingForInput)
            webView.stopLoading()
            webView.destroy()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val IDP_SETTLE_MS = 8_000L
    }
}
