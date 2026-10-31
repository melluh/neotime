package com.melluh.neotime.timeedit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Base64

/** The claims neotime uses from TimeEdit's auth token (te_auth_token, an HS256 JWT). */
@Serializable
data class TokenClaims(
    val username: String? = null,
    val email: String? = null,
    val iat: Long = 0,
    val exp: Long = 0,
) {
    val expiresAt: Instant get() = Instant.ofEpochSecond(exp)
    fun isExpired(now: Instant = Instant.now()): Boolean = !now.isBefore(expiresAt)
}

object Jwt {
    private val json = Json { ignoreUnknownKeys = true }

    /** Decodes the payload without verifying the signature (only TimeEdit holds the key). */
    fun claims(token: String): TokenClaims {
        val payload = token.split('.').getOrNull(1) ?: throw TimeEditException("Malformed token")
        return json.decodeFromString(String(Base64.getUrlDecoder().decode(payload)))
    }
}

@Serializable
data class Account(
    val displayName: String?,
    val studentNumber: String?,
    val email: String?,
)

object AccountParser {
    private val USER_INFO = Regex("""User:\s*([^,"]+?)\s*,[^"]*?,\s*([a-zA-Z]\d+)\s*"""")

    /**
     * Reads the display name and user id from the "techinfo" block on logged-in pages:
     * data-info="Viewer \n User: Firstname Lastname, 12,345, s1234567".
     */
    fun fromPage(html: String): Pair<String, String>? =
        USER_INFO.find(html)?.destructured?.let { (name, id) -> name.trim() to id }
}
