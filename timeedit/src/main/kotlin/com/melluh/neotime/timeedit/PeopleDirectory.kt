package com.melluh.neotime.timeedit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.text.Normalizer

/**
 * A person's public UT profile. Most photos are AVIF; about 4 in 10 people have none.
 * [displayName] is null only in entries cached before it was added.
 */
@Serializable
data class PersonMatch(val profileUrl: String, val photoUrl: String? = null, val displayName: String? = null)

/**
 * What a person's profile page adds. Its [photoUrl] is twice the size of the search result's.
 * [courses] holds the Osiris codes taught per academic year, by the year it starts in.
 */
@Serializable
data class PersonDetails(
    val photoUrl: String? = null,
    val email: String? = null,
    val offices: List<String> = emptyList(),
    val courses: Map<Int, Set<String>> = emptyMap(),
)

/** One of several people with the same name; their [details] are what tells them apart. */
@Serializable
data class Namesake(val match: PersonMatch, val details: PersonDetails) {
    fun teaches(courseCodes: Set<String>, academicYear: Int): Boolean =
        details.courses[academicYear].orEmpty().any { it in courseCodes }
}

/** The result of a search: the one person with a name, or the [namesakes] who share it. */
data class PersonLookup(val match: PersonMatch? = null, val namesakes: List<Namesake> = emptyList())

/** TimeEdit lists roles such as "*Unsupervised" in the lecturer column; those aren't people. */
fun isPlaceholderLecturer(name: String): Boolean = name.startsWith("*")

/**
 * Finds lecturers on UT's public people pages. Profile slugs can't be derived from a name
 * ("f.castor", "g.g.c.caltais"), so this searches and picks the result itself: the site's
 * search is fuzzy and returns up to 100 loosely related people for any name.
 */
class PeopleDirectory(
    private val http: OkHttpClient,
    private val base: HttpUrl = DEFAULT_BASE,
) {
    /** The site's own results page, for names [lookup] can't pin to one person. */
    fun searchUrl(name: String): String = base.newBuilder()
        .addPathSegment("overview")
        .addQueryParameter("query", name)
        .build()
        .toString()

    /** Finds who is called [name]. Several people means reading each one's profile as well. */
    suspend fun lookup(name: String): PersonLookup {
        val people = matches(name, parseTiles(download(searchUrl(name)))).map { person(it, fallbackName = name) }
        if (people.size < 2) return PersonLookup(people.singleOrNull())
        return PersonLookup(
            namesakes = coroutineScope {
                people.map { async { Namesake(it, details(it.profileUrl)) } }.awaitAll()
            },
        )
    }

    /**
     * Finds who has the room with this MazeMap identifier as their office. Quoted room codes
     * narrow the search far better than building names do, but it's still looser than an office:
     * departments turn up too, and a person with two offices is only found by the first one.
     */
    suspend fun inRoom(identifier: String): List<PersonMatch> =
        RoomNames.officeSearchTerms(identifier)
            .flatMap { parseTiles(download(searchUrl("\"$it\""))) }
            .filter { tile ->
                !tile.slug.startsWith(DEPARTMENT_SLUG) && tile.offices.any { RoomNames.officeIdentifier(it) == identifier }
            }
            .distinctBy { it.slug }
            .map { person(it, fallbackName = initialledName(it.title)) }

    /** Reads the profile page at [profileUrl], as returned by [lookup]. */
    suspend fun details(profileUrl: String): PersonDetails = parseDetails(download(profileUrl))

    private fun person(tile: Tile, fallbackName: String): PersonMatch {
        val profile = base.newBuilder().addPathSegment(tile.slug).build().toString()
        return PersonMatch(profile, tile.photoUrl, fullName(tile.title) ?: fallbackName)
    }

    private suspend fun download(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", TimeEditClient.USER_AGENT).build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("People page failed: HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    /** Offices come from the visiting addresses, because the page's summary names only one. */
    internal fun parseDetails(html: String): PersonDetails {
        val page = Jsoup.parse(html)
        return PersonDetails(
            photoUrl = page.selectFirst("img.pageheader__insetimage")?.attr("src")?.takeIf { it.isNotEmpty() },
            email = page.selectFirst(".page-sidebar__panel--specsheet a[href^=mailto:]")?.text()?.takeIf { it.isNotEmpty() },
            offices = page.select("#address-visiting .person-address-data").mapNotNull { address ->
                val line = address.selectFirst("p.normal")?.textNodes()?.firstOrNull()?.text()?.trim()
                line?.takeIf { it.isNotEmpty() }?.let(::office)
            }.distinct(),
            courses = page.select("#tabpanel-education a[href*=cursuscode=]")
                .mapNotNull { link -> course(link.attr("href")) }
                .groupBy({ (year, _) -> year }, { (_, code) -> code })
                .mapValues { (_, codes) -> codes.toSet() },
        )
    }

    /** The academic year and code in a link to the Osiris course catalogue. */
    private fun course(link: String): Pair<Int, String>? {
        val url = link.toHttpUrlOrNull() ?: return null
        val year = url.queryParameter("collegejaar")?.toIntOrNull() ?: return null
        return url.queryParameter("cursuscode")?.let { year to it }
    }

    /** "Zilverling 3035" for "Zilverling (building no. 11), room 3035", as in search results. */
    private fun office(address: String): String {
        val (building, room) = ADDRESS_LINE.matchEntire(address)?.destructured ?: return address
        return "$building $room".trim()
    }

    internal data class Tile(
        val slug: String,
        val title: String,
        val photoUrl: String?,
        val offices: List<String> = emptyList(),
    )

    internal fun parseTiles(html: String): List<Tile> =
        Jsoup.parse(html).select("div.ut-person-tile").mapNotNull { tile ->
            val slug = tile.attr("data-link").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = tile.selectFirst("h3.ut-person-tile__title")?.text() ?: return@mapNotNull null
            Tile(
                slug,
                title,
                tile.selectFirst("img.ut-person-tile__image")?.attr("src")?.takeIf { it.isNotEmpty() },
                tile.select(".ut-person-tile__location div").map { it.text() },
            )
        }

    /** Requires an exact name match, as the search's own results are only loosely related. */
    internal fun matches(name: String, tiles: List<Tile>): List<Tile> {
        val wanted = normalize(name)
        return tiles.filter { wanted in names(it.title) }
    }

    /** "Jong, J. de (Jorn)" is both "J. de Jong" and "Jorn de Jong"; TimeEdit uses either. */
    private fun names(title: String): Set<String> {
        val parsed = TITLE.matchEntire(title.trim()) ?: return emptySet()
        val (surname, initials, prefix, firstName) = parsed.destructured
        return listOf(initials, firstName)
            .filter { it.isNotEmpty() }
            .map { normalize("$it $prefix $surname") }
            .toSet()
    }

    /**
     * "Bos, P. van den (Petra)" is "Petra van den Bos". Null when the title has no first name.
     * A nickname nested in the parentheses is dropped: "(Yongjian(Tommy))" is "Yongjian".
     */
    private fun fullName(title: String): String? {
        val parsed = TITLE.matchEntire(title.trim()) ?: return null
        val (surname, _, prefix, firstName) = parsed.destructured
        val given = firstName.substringBefore('(').trim()
        if (given.isEmpty()) return null
        return "$given $prefix $surname".trim().replace(WHITESPACE, " ")
    }

    /** "Dijk, T. van" is "T. van Dijk", for when there's no name to search by to fall back on. */
    private fun initialledName(title: String): String {
        val parsed = TITLE.matchEntire(title.trim()) ?: return title
        val (surname, initials, prefix) = parsed.destructured
        return "$initials $prefix $surname".trim().replace(WHITESPACE, " ")
    }

    /**
     * Compares letters and digits only, because the two sites punctuate differently:
     * "L.J Pronk" is "L.J." and "Arenas-Oude Alink" is "Arenas - Oude Alink" on the people pages.
     * TimeEdit's apostrophes are modifier letters (U+02BC), so those don't count as letters here.
     */
    private fun normalize(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(NOT_NAME_CHARACTER, "").lowercase()

    private companion object {
        val DEFAULT_BASE = "https://people.utwente.nl/".toHttpUrl()
        const val DEPARTMENT_SLUG = "business/"

        /**
         * Surname, initials (sometimes spaced, "R. N. N."), optional prefix ("van den"),
         * optional first name in parentheses.
         */
        val TITLE = Regex("""([^,]+),\s*(\S+(?:\s+\p{Lu}\.\S*)*)\s*([^(]*?)\s*(?:\((.*)\))?""")
        val WHITESPACE = Regex("""\s+""")
        val NOT_NAME_CHARACTER = Regex("""(?:[^\p{L}\p{N}]|\p{Lm})+""")

        /** The building, its number, and usually a room. */
        val ADDRESS_LINE = Regex("""(.+?) \(building no\. [^)]*\)(?:, room (.+))?""")
    }
}
