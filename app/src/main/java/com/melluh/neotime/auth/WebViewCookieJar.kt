package com.melluh.neotime.auth

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * OkHttp CookieJar backed by the WebView's CookieManager, so cookies set during a
 * WebView login are sent by OkHttp and vice versa.
 *
 * CookieManager.getCookie() only returns "name=value" pairs (no expiry/flags), so the
 * cookies handed to OkHttp are reconstructed without attributes.
 */
class WebViewCookieJar(
    private val cookieManager: CookieManager = CookieManager.getInstance(),
) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val target = url.toString()
        for (cookie in cookies) cookieManager.setCookie(target, cookie.toString())
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = cookieManager.getCookie(url.toString()) ?: return emptyList()
        return header.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
