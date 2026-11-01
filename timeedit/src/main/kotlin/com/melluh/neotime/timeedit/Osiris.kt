package com.melluh.neotime.timeedit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/** A course the student is registered for in OSIRIS. [code] is the number TimeEdit shows too. */
data class OsirisRegistration(
    val code: String,
    val name: String,
    val year: String? = null,
    val block: String? = null,
) {
    /**
     * Picks this course from a TimeEdit search for [code]. TimeEdit names read like
     * "Calculus 1 for AT, 202001212, 2025, 1A": one code can have an object per year and block,
     * and last year's object has no events this year, so a different year is never a match.
     */
    fun matchIn(refs: List<ObjectRef>): ObjectRef? {
        val candidates = refs.map { it to it.name.split(NAME_SEPARATOR) }
            .filter { (_, tokens) -> code in tokens }
            .filter { (_, tokens) ->
                val years = tokens.filter(YEAR::matches)
                year == null || years.isEmpty() || year in years
            }
        return (candidates.firstOrNull { (_, tokens) -> block != null && block in tokens } ?: candidates.firstOrNull())?.first
    }

    private companion object {
        val NAME_SEPARATOR = Regex("""[,.\s]+""")
        val YEAR = Regex("""20\d\d""")
    }
}

data class OsirisToken(val authorization: String, val expiresAt: Instant?) {
    fun isExpired(now: Instant = Instant.now()): Boolean = expiresAt != null && now >= expiresAt
}

/** Client for the API behind UT's OSIRIS Student web app. It is CACI's private API, not a public one. */
class OsirisClient(
    private val http: OkHttpClient,
    private val base: HttpUrl = DEFAULT_BASE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The URL that starts a sign-in. OSIRIS hands it out in the 401 for any unauthenticated call. */
    suspend fun loginUrl(): String = withContext(Dispatchers.IO) {
        http.newCall(request(registrationsUrl(0)).build()).execute().use { r ->
            if (r.code != 401) throw TimeEditException("OSIRIS answered HTTP ${r.code} without a token")
            json.decodeFromString<RawLoginChallenge>(r.body?.string().orEmpty()).redirectUrl
        }
    }

    /**
     * True for the page sign-in ends on: the root of the OSIRIS site, its OAuth redirect_uri. The
     * rest of that host is not the end, because the SAML response is posted there on the way.
     */
    fun isCallback(url: String?): Boolean = url?.toHttpUrlOrNull()?.let { it.host == base.host && it.encodedPath == "/" } == true

    /**
     * The authorization code in a [isCallback] URL, or null when the sign-in failed. The server
     * sends it in the fragment ("/#code=..."); the web client's startup script moves it to the query.
     */
    fun callbackCode(url: String?): String? {
        val callback = url?.takeIf(::isCallback)?.toHttpUrlOrNull() ?: return null
        return callback.queryParameter("code")
            ?: callback.newBuilder().encodedQuery(callback.encodedFragment).build().queryParameter("code")
    }

    /** Trades a sign-in code for an access token, with the same request as OSIRIS's own web client. */
    suspend fun exchangeCode(code: String, now: Instant = Instant.now()): OsirisToken = withContext(Dispatchers.IO) {
        val body = json.encodeToString(RawTokenRequest.serializer(), RawTokenRequest(code, redirectUri = ""))
        val request = request(base.newBuilder().addPathSegment("token").build())
            .post(body.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw TimeEditException("OSIRIS rejected the sign-in code (HTTP ${r.code})")
            val raw = json.decodeFromString<RawToken>(r.body?.string().orEmpty())
            val accessToken = raw.accessToken ?: throw TimeEditException("OSIRIS returned no access token")
            OsirisToken(
                authorization = "${raw.tokenType.replaceFirstChar(Char::uppercase)} $accessToken",
                expiresAt = raw.expiresIn?.contentOrNull?.toLongOrNull()?.let { now.plusSeconds(it - EXPIRY_MARGIN_SECONDS) },
            )
        }
    }

    /** Fetches the current course registrations; throws [NotLoggedInException] for a dead [token]. */
    suspend fun registrations(token: OsirisToken): List<OsirisRegistration> = withContext(Dispatchers.IO) {
        val registrations = mutableListOf<RawRegistration>()
        repeat(MAX_PAGES) {
            val request = request(registrationsUrl(registrations.size)).header("Authorization", token.authorization).build()
            val page = http.newCall(request).execute().use { r ->
                if (r.code == 401) throw NotLoggedInException()
                if (!r.isSuccessful) throw TimeEditException("OSIRIS answered HTTP ${r.code}")
                json.decodeFromString<RawRegistrations>(r.body?.string().orEmpty())
            }
            registrations += page.items
            if (!page.hasMore || page.items.isEmpty()) return@withContext registrations.toModel()
        }
        registrations.toModel()
    }

    private fun List<RawRegistration>.toModel(): List<OsirisRegistration> = mapNotNull { raw ->
        val code = raw.code?.trim()?.ifEmpty { null } ?: return@mapNotNull null
        OsirisRegistration(
            code = code,
            name = listOfNotNull(raw.shortName, raw.longName).firstOrNull { it.isNotBlank() }?.trim() ?: code,
            year = raw.year?.contentOrNull,
            block = raw.block?.contentOrNull,
        )
    }.distinct()

    private fun registrationsUrl(offset: Int): HttpUrl = base.newBuilder()
        .addPathSegments("student/inschrijvingen/cursussen")
        .addQueryParameter("toon_historie", "N")
        .addQueryParameter("limit", PAGE_SIZE.toString())
        .apply { if (offset > 0) addQueryParameter("offset", offset.toString()) }
        .build()

    private fun request(url: HttpUrl) = Request.Builder().url(url)
        .header("User-Agent", TimeEditClient.USER_AGENT)
        .header("Accept", "application/json")
        .header("taal", "EN")
        .header("client_type", "web")

    companion object {
        val DEFAULT_BASE = "https://utwente.osiris-student.nl/student/osiris".toHttpUrl()
        private val JSON = "application/json".toMediaType()
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 10
        private const val EXPIRY_MARGIN_SECONDS = 60L
    }
}

@Serializable
private data class RawLoginChallenge(@SerialName("Authenticate-Redirect-Url") val redirectUrl: String)

/** The web client sends a redirect_uri relative to its origin: empty when it landed on the root. */
@Serializable
private data class RawTokenRequest(val code: String, @SerialName("redirect_uri") val redirectUri: String)

@Serializable
private data class RawToken(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: JsonPrimitive? = null,
)

@Serializable
private data class RawRegistrations(val items: List<RawRegistration> = emptyList(), val hasMore: Boolean = false)

@Serializable
private data class RawRegistration(
    @SerialName("cursus") val code: String? = null,
    @SerialName("cursus_korte_naam") val shortName: String? = null,
    @SerialName("cursus_lange_naam") val longName: String? = null,
    @SerialName("collegejaar") val year: JsonPrimitive? = null,
    @SerialName("blok") val block: JsonPrimitive? = null,
)
