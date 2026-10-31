package com.melluh.neotime.timeedit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.text.Normalizer

/** A person's public UT profile. Most photos are AVIF; about 4 in 10 people have none. */
@Serializable
data class PersonMatch(val profileUrl: String, val photoUrl: String? = null)

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

    /** Returns the one person called [name], or null when none or several match. */
    suspend fun lookup(name: String): PersonMatch? {
        val html = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(searchUrl(name)).header("User-Agent", TimeEditClient.USER_AGENT).build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw IOException("People search failed: HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        }
        val tile = match(name, parseTiles(html)) ?: return null
        val profile = base.newBuilder().addPathSegment(tile.slug).build().toString()
        return PersonMatch(profile, tile.photoUrl)
    }

    internal data class Tile(val slug: String, val title: String, val photoUrl: String?)

    internal fun parseTiles(html: String): List<Tile> =
        Jsoup.parse(html).select("div.ut-person-tile").mapNotNull { tile ->
            val slug = tile.attr("data-link").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = tile.selectFirst("h3.ut-person-tile__title")?.text() ?: return@mapNotNull null
            Tile(slug, title, tile.selectFirst("img.ut-person-tile__image")?.attr("src")?.takeIf { it.isNotEmpty() })
        }

    /**
     * Requires an exact name match. An ambiguous name returns null rather than the first hit,
     * because showing the wrong person's face is worse than showing none.
     */
    internal fun match(name: String, tiles: List<Tile>): Tile? {
        val wanted = normalize(name)
        return tiles.filter { wanted in names(it.title) }.singleOrNull()
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
     * Compares letters and digits only, because the two sites punctuate differently:
     * "L.J Pronk" is "L.J." and "Arenas-Oude Alink" is "Arenas - Oude Alink" on the people pages.
     */
    private fun normalize(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(NOT_NAME_CHARACTER, "").lowercase()

    private companion object {
        val DEFAULT_BASE = "https://people.utwente.nl/".toHttpUrl()

        /** Surname, initials, optional prefix ("van den"), optional first name in parentheses. */
        val TITLE = Regex("""([^,]+),\s*(\S+)\s*([^(]*?)\s*(?:\((.*)\))?""")
        val NOT_NAME_CHARACTER = Regex("""[^\p{L}\p{N}]+""")
    }
}
