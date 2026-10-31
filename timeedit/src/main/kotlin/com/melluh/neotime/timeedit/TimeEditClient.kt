package com.melluh.neotime.timeedit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Public pages need no login; student pages need the TimeEdit viewer session. */
enum class Area(val path: String) { PUBLIC("public"), STUDENT("student") }

/**
 * Client for UT's TimeEdit viewer. In the app, [http] shares cookies with the login WebView,
 * so STUDENT requests are authenticated after login.
 */
class TimeEditClient(
    private val http: OkHttpClient,
    private val base: HttpUrl = DEFAULT_BASE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    internal fun url(area: Area, page: String): HttpUrl.Builder = base.newBuilder()
        .addPathSegment(area.path)
        .apply { if (page.isEmpty()) addPathSegment("") else addPathSegments(page) }
        .addQueryParameter("l", "en_US")

    internal suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (isLoginGate(r.code, body)) throw NotLoggedInException()
            if (!r.isSuccessful) throw TimeEditException("HTTP ${r.code} for ${url.encodedPath}")
            body
        }
    }

    /**
     * Fetches the schedule of [objects] between [from] and [to] (inclusive) in view [sid].
     * Splits the range when TimeEdit truncates at its reservation limit.
     */
    suspend fun schedule(area: Area, sid: Int, from: LocalDate, to: LocalDate, objects: List<String>): List<Event> {
        if (objects.isEmpty()) return emptyList()
        val url = url(area, "ri.json")
            .addQueryParameter("h", "t")
            .addQueryParameter("sid", sid.toString())
            .addQueryParameter("p", "${from.format(PERIOD)}.x,${to.format(PERIOD)}.x")
            .addQueryParameter("objects", objects.joinToString(","))
            .build()
        val parsed = ScheduleParser.parse(get(url))
        val days = ChronoUnit.DAYS.between(from, to)
        if (!parsed.truncated || days < 1) return parsed.events
        val mid = from.plusDays(days / 2)
        return (schedule(area, sid, from, mid, objects) + schedule(area, sid, mid.plusDays(1), to, objects))
            .distinctBy { it.id }
    }

    /** Searches schedulable objects. STUDENT search needs login but returns cleaner data. */
    suspend fun search(area: Area, sid: Int, type: ObjectType, text: String, max: Int = 50): List<ObjectRef> {
        val builder = url(area, if (area == Area.STUDENT) "objects.json" else "objects.html")
            .addQueryParameter("max", max.toString())
            .addQueryParameter("fr", if (area == Area.STUDENT) "f" else "t")
            .addQueryParameter("partajax", "t")
            .addQueryParameter("im", "f")
            .addQueryParameter("sid", sid.toString())
            .addQueryParameter("types", type.id.toString())
            .addQueryParameter("search_text", text)
        if (area == Area.STUDENT) builder.addQueryParameter("part", "t")
        val body = get(builder.build())
        return if (area == Area.STUDENT) parseObjectsJson(body) else parseObjectsHtml(body)
    }

    internal fun parseObjectsJson(body: String): List<ObjectRef> =
        json.decodeFromString<RawObjects>(body).objects.map { o ->
            ObjectRef(o.idAndType, o.fields.values.joinToString(", ") { it.jsonPrimitive.content.trim() })
        }

    internal fun parseObjectsHtml(body: String): List<ObjectRef> =
        Jsoup.parse(body).select("[data-id][data-name]").map { ObjectRef(it.attr("data-id"), it.attr("data-name")) }
            .filter { it.id.contains('.') }
            .distinctBy { it.id }

    /**
     * Creates a viewer session from a TimeEdit auth token (the JWT from the te_auth_token cookie),
     * the same handoff TimeEdit's own page does after login.
     */
    suspend fun exchangeToken(token: String): Boolean = withContext(Dispatchers.IO) {
        val target = base.newBuilder().addPathSegment(Area.STUDENT.path).addPathSegment("").build()
        val request = Request.Builder().url(target)
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("teauthtoken", token).build())
            .build()
        http.newCall(request).execute().use { it.isSuccessful }
    }

    /** Fetches the student landing page; throws [NotLoggedInException] when the session is gone. */
    suspend fun studentLanding(): String = get(url(Area.STUDENT, "").build())

    suspend fun landing(area: Area): String = get(url(area, "").build())

    suspend fun page(area: Area, path: String): String = get(url(area, path).build())

    companion object {
        val DEFAULT_BASE = "https://cloud.timeedit.net/nl_utwente/web".toHttpUrl()
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) neotime"
        private val PERIOD = DateTimeFormatter.BASIC_ISO_DATE

        /** TimeEdit answers a missing session with HTTP 412/401 and its TE Auth login page. */
        fun isLoginGate(code: Int, body: String): Boolean =
            code == 412 || code == 401 || body.contains("AUTH_TOKEN_NAME")
    }
}

@Serializable
internal data class RawObjects(val objects: List<RawObject> = emptyList())

@Serializable
internal data class RawObject(val idAndType: String, val fields: JsonObject = JsonObject(emptyMap()))
