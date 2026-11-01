package com.melluh.neotime.auth

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.melluh.neotime.timeedit.Account
import com.melluh.neotime.timeedit.AccountParser
import com.melluh.neotime.timeedit.Jwt
import com.melluh.neotime.timeedit.NotLoggedInException
import com.melluh.neotime.timeedit.TimeEditClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.resume

sealed interface SessionState {
    /** Never logged in, or logged out explicitly: the app uses public data. */
    data object LoggedOut : SessionState

    data class LoggedIn(val account: Account, val tokenExpiresAt: Instant?) : SessionState

    /** Background renewal in progress; shown to the user, never silent. */
    data class Renewing(val step: String, val account: Account?) : SessionState

    /** Renewal needs the user (password, MFA, account picker). */
    data class NeedsInteractiveLogin(val account: Account?) : SessionState
}

/** URLs and cookies of UT's TimeEdit login chain (TE Auth → SURFconext → Microsoft). */
object TimeEditLogin {
    const val STUDENT_URL = "https://cloud.timeedit.net/nl_utwente/web/student/"
    private const val TOKEN_URL = "https://www.timeedit.net/"
    private const val ORG = "65c9fb5409260c71ccd98402"

    /** Starts the SURFconext sign-in directly, skipping TimeEdit's login chooser. */
    val SSO_URL = "https://auth.timeedit.net/v1/organizations/$ORG/authenticate" +
        "?organizationId=$ORG&appId=TE_VIEWER&language=en" +
        "&context=" + Uri.encode(STUDENT_URL) +
        "&authConfigId=65c9fb5639e2c1126c01593e&region=EU_EES"

    private val TIMEEDIT_URLS = listOf(TOKEN_URL, "https://auth.timeedit.net/", STUDENT_URL)

    /** The JWT set by TimeEdit after a successful sign-in (a session cookie on www.timeedit.net). */
    fun token(): String? = CookieManager.getInstance().getCookie(TOKEN_URL)
        ?.split(';')?.map { it.trim() }
        ?.firstOrNull { it.startsWith("te_auth_token=") }
        ?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    /** Pages where the identity provider may be waiting for user input. */
    fun isIdentityProvider(url: String?): Boolean {
        val host = url?.let { Uri.parse(it).host } ?: return false
        return host.endsWith("microsoftonline.com") || host.endsWith("surfconext.nl") || host.endsWith("microsoft.com")
    }

    /**
     * Expires TimeEdit's own cookies (token and viewer session) but keeps the Microsoft and
     * SURFconext ones, so the next sign-in can complete without input.
     */
    fun expireTimeEditCookies() {
        val cm = CookieManager.getInstance()
        val paths = listOf("/", "/nl_utwente", "/nl_utwente/", "/nl_utwente/web", "/nl_utwente/web/",
            "/nl_utwente/web/student", "/nl_utwente/web/student/")
        val domains = listOf("", "; Domain=.timeedit.net", "; Domain=timeedit.net")
        for (url in TIMEEDIT_URLS) {
            val names = cm.getCookie(url)?.split(';')?.map { it.trim().substringBefore('=') }.orEmpty()
            for (name in names) for (path in paths) for (domain in domains) {
                cm.setCookie(url, "$name=; Max-Age=0; Path=$path; Secure$domain")
            }
        }
        cm.flush()
    }

    suspend fun clearAllCookies() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            CookieManager.getInstance().removeAllCookies { cont.resume(Unit) }
        }
        CookieManager.getInstance().flush()
    }
}

/**
 * Keeps the TimeEdit session alive, in this order: reuse the session, re-post the stored
 * token, re-run the sign-in in an offscreen WebView, and finally ask the user. Every step that
 * acts on the account is published through [state] so the UI can show it.
 */
class SessionManager(
    private val context: Context,
    private val client: TimeEditClient,
    private val store: SessionStore,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Short-lived messages for the banner, e.g. "Signed in again". */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices

    private val mutex = Mutex()

    /** Completes once [restore] has run, so a launch-time refresh can't mistake a saved login for none. */
    private val restored = CompletableDeferred<Unit>()

    suspend fun restore() {
        try {
            val account = store.account() ?: return
            val expiry = store.token()?.let { runCatching { Jwt.claims(it).expiresAt }.getOrNull() }
            _state.value = SessionState.LoggedIn(account, expiry)
        } finally {
            restored.complete(Unit)
        }
    }

    /** True when STUDENT requests will work. Never prompts; sets [SessionState.NeedsInteractiveLogin] instead. */
    suspend fun ensureSession(): Boolean = mutex.withLock {
        restored.await()
        val before = _state.value
        val account = before.account() ?: return false // never logged in: don't touch the IdP
        try {
            if (checkSession()) return true
            // Already waiting for the user: don't re-run the sign-in on every refresh
            if (before is SessionState.NeedsInteractiveLogin) return false

            store.token()?.takeUnless { runCatching { Jwt.claims(it).isExpired() }.getOrDefault(true) }?.let { token ->
                _state.value = SessionState.Renewing("Refreshing TimeEdit session", account)
                if (capture(token, account)) return notice("TimeEdit session refreshed")
            }

            _state.value = SessionState.Renewing("Signing in again via UT", account)
            TimeEditLogin.expireTimeEditCookies()
            val token = runOffscreenSignIn()
            if (token != null && capture(token, account)) return notice("Signed in again")

            _state.value = SessionState.NeedsInteractiveLogin(account)
            false
        } catch (e: IOException) {
            _state.value = before // offline: keep the last known state, use the cache
            false
        }
    }

    /** Called by the login screen once the interactive sign-in produced a token. */
    suspend fun completeInteractiveLogin(): Boolean = mutex.withLock {
        val token = TimeEditLogin.token() ?: return false
        try {
            capture(token, _state.value.account())
        } catch (e: IOException) {
            false
        }
    }

    suspend fun logout() = mutex.withLock {
        TimeEditLogin.clearAllCookies()
        store.clear()
        _state.value = SessionState.LoggedOut
    }

    /** Debug: simulate an expired TimeEdit session (Microsoft sign-in stays valid). */
    suspend fun debugExpireTimeEditSession() = mutex.withLock {
        withContext(Dispatchers.Main) { TimeEditLogin.expireTimeEditCookies() }
        store.clearToken()
    }

    /** Debug: drop every login cookie, so renewal needs the user. */
    suspend fun debugClearAllLoginCookies() = mutex.withLock {
        TimeEditLogin.clearAllCookies()
        store.clearToken()
    }

    private suspend fun checkSession(): Boolean = try {
        client.studentLanding()
        val account = _state.value.account()
        if (account != null && _state.value !is SessionState.LoggedIn) {
            val expiry = store.token()?.let { runCatching { Jwt.claims(it).expiresAt }.getOrNull() }
            _state.value = SessionState.LoggedIn(account, expiry)
        }
        true
    } catch (e: NotLoggedInException) {
        false
    }

    /** Turns a token into a viewer session, then records the account it belongs to. */
    private suspend fun capture(token: String, previous: Account?): Boolean {
        val claims = runCatching { Jwt.claims(token) }.getOrNull() ?: return false
        if (!client.exchangeToken(token)) return false
        val landing = try {
            client.studentLanding()
        } catch (e: NotLoggedInException) {
            return false
        }
        val parsed = AccountParser.fromPage(landing)
        val account = Account(
            displayName = parsed?.first ?: previous?.displayName,
            studentNumber = claims.username ?: parsed?.second ?: previous?.studentNumber,
            email = claims.email ?: previous?.email,
        )
        store.save(token, account)
        _state.value = SessionState.LoggedIn(account, claims.expiresAt)
        return true
    }

    private fun notice(message: String): Boolean {
        _notices.tryEmit(message)
        return true
    }

    /**
     * Loads the SSO URL in a WebView that is never shown. Returns the new token, or null when the
     * identity provider stops on a page that needs input (or the attempt times out).
     */
    private suspend fun runOffscreenSignIn(): String? = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>()
        val handler = Handler(Looper.getMainLooper())
        val waitingForInput = Runnable { result.complete(null) }
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    handler.removeCallbacks(waitingForInput)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    TimeEditLogin.token()?.let { result.complete(it); return }
                    // The IdP normally redirects on by itself within a second; if it sits still,
                    // it wants a password, MFA or an account choice.
                    if (TimeEditLogin.isIdentityProvider(url)) handler.postDelayed(waitingForInput, IDP_SETTLE_MS)
                }
            }
        }
        try {
            webView.loadUrl(TimeEditLogin.SSO_URL)
            withTimeoutOrNull(OFFSCREEN_TIMEOUT_MS) { result.await() }
        } finally {
            handler.removeCallbacks(waitingForInput)
            webView.stopLoading()
            webView.destroy()
        }
    }

    private fun SessionState.account(): Account? = when (this) {
        is SessionState.LoggedIn -> account
        is SessionState.Renewing -> account
        is SessionState.NeedsInteractiveLogin -> account
        SessionState.LoggedOut -> null
    }

    private companion object {
        const val OFFSCREEN_TIMEOUT_MS = 30_000L
        const val IDP_SETTLE_MS = 8_000L
    }
}
