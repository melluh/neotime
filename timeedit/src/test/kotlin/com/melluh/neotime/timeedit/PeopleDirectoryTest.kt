package com.melluh.neotime.timeedit

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class PeopleDirectoryTest {
    private val server = MockWebServer()
    private lateinit var directory: PeopleDirectory

    @Before
    fun setUp() {
        server.start()
        directory = PeopleDirectory(OkHttpClient(), server.url("/"))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun tiles(fixture: String) = directory.parseTiles(Fixtures.read("people/$fixture"))

    private fun tile(title: String, slug: String = "slug", photo: String? = null) = PeopleDirectory.Tile(slug, title, photo)

    @Test
    fun `parses every tile on a real results page`() {
        val tiles = tiles("search_t_van_dijk.html")
        assertEquals(100, tiles.size)
        val tom = tiles.first()
        assertEquals("t.vandijk", tom.slug)
        assertEquals("Dijk, T. van (Tom)", tom.title)
        assertTrue(tom.photoUrl!!.endsWith("/tvandijk.avif"))
    }

    private fun details(fixture: String) = directory.parseDetails(Fixtures.read("people/$fixture"))

    private fun PeopleDirectory.match(name: String, tiles: List<PeopleDirectory.Tile>) = matches(name, tiles).singleOrNull()

    @Test
    fun `a profile has the larger photo, email and office`() {
        val details = details("profile_t_van_dijk.html")
        assertTrue(details.photoUrl!!.endsWith("/tvandijk.avif"))
        assertTrue("0c016340014001" in details.photoUrl!!) // the 320px rendition, not the building
        assertEquals("t.vandijk@utwente.nl", details.email)
        assertEquals(listOf("Zilverling 3035"), details.offices)
    }

    @Test
    fun `every visiting address is an office, the mailing address isn't`() {
        val details = details("profile_j_van_loon.html")
        assertEquals(listOf("Spiegel 418", "Ravelijn 1438", "Zilverling 1057"), details.offices)
    }

    @Test
    fun `a profile without a photo has none, rather than the building's`() {
        val details = details("profile_y_van_dijk.html")
        assertNull(details.photoUrl)
        assertEquals("y.vandijk@utwente.nl", details.email)
        assertEquals(listOf("Langezijds 2418"), details.offices)
    }

    @Test
    fun `an address without a room is just the building`() {
        val html = """<div id="address-visiting"><div class="person-address-data"><h3>University of Twente</h3>
            <p class="normal">Carré (building no. 15)<br/>Hallenweg 23</p></div></div>"""
        assertEquals(listOf("Carré"), directory.parseDetails(html).offices)
    }

    @Test
    fun `details are read from the profile page`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("people/profile_t_van_dijk.html")))
        val details = directory.details(server.url("/t.vandijk").toString())
        assertEquals("t.vandijk@utwente.nl", details.email)
        assertEquals("/t.vandijk", server.takeRequest().path)
    }

    @Test
    fun `lookup returns the profile and photo of the one match`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("people/search_t_van_dijk.html")))
        val match = directory.lookup("T. van Dijk").match
        assertEquals(server.url("/t.vandijk").toString(), match!!.profileUrl)
        assertTrue(match.photoUrl!!.startsWith("https://utwente.becdn.net/"))
        assertEquals("Tom van Dijk", match.displayName)
    }

    private suspend fun displayName(name: String, title: String): String? {
        server.enqueue(
            MockResponse().setBody(
                """<div class="ut-person-tile" data-link="slug"><h3 class="ut-person-tile__title">$title</h3></div>""",
            ),
        )
        return directory.lookup(name).match?.displayName
    }

    @Test
    fun `display name uses the first name instead of initials`() = runTest {
        assertEquals("Roy Damgrave", displayName("R.G.J. Damgrave", "Damgrave, R.G.J. (Roy)"))
        assertEquals("Petra van den Bos", displayName("P. van den Bos", "Bos, P. van den (Petra)"))
        assertEquals("Robbin Jan van Duijne", displayName("R.J. van Duijne", "Duijne, R.J. van (Robbin Jan)"))
    }

    @Test
    fun `display name leaves out spaced initials`() = runTest {
        assertEquals("Rodrigo Nogueira Nobre", displayName("R.N.N. Nogueira Nobre", "Nogueira Nobre, R. N. N. (Rodrigo)"))
    }

    @Test
    fun `display name leaves out a nested nickname`() = runTest {
        assertEquals("Yongjian Tao", displayName("Y. Tao", "Tao, Y. (Yongjian(Tommy))"))
    }

    @Test
    fun `display name falls back to the given name without a first name`() = runTest {
        assertEquals("J. Doe", displayName("J. Doe", "Doe, J."))
        assertEquals("Y.L. Lyu", displayName("Y.L. Lyu", "Lyu, Y.L. ()"))
    }

    @Test
    fun `lookup encodes the name as the search query`() = runTest {
        server.enqueue(MockResponse().setBody("<html></html>"))
        directory.lookup("P. van den Bos")
        val path = server.takeRequest().requestUrl!!
        assertEquals("/overview", path.encodedPath)
        assertEquals("P. van den Bos", path.queryParameter("query"))
    }

    @Test
    fun `person without a photo still matches`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("people/search_van_dijk.html")))
        val match = directory.lookup("Y. van Dijk").match
        assertEquals(server.url("/y.vandijk").toString(), match!!.profileUrl)
        assertNull(match.photoUrl)
    }

    @Test
    fun `an ambiguous name is everyone with it`() {
        val tiles = tiles("search_y_li.html")
        assertEquals(tiles.count { it.title.startsWith("Li, Y. (") }, directory.matches("Y. Li", tiles).size)
        assertTrue(directory.matches("Y. Li", tiles).size > 1)
    }

    private fun serveDeJongs() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                "/overview" -> MockResponse().setBody(Fixtures.read("people/search_j_de_jong.html"))
                "/j.dejong-3" -> MockResponse().setBody(Fixtures.read("people/profile_jasper_de_jong.html"))
                "/jorn.dejong" -> MockResponse().setBody(Fixtures.read("people/profile_jorn_de_jong.html"))
                "/jan-willem.dejong" -> MockResponse().setBody(Fixtures.read("people/profile_jan_willem_de_jong.html"))
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private suspend fun deJongs() = directory.lookup("J. de Jong").namesakes.associateBy { it.match.displayName }

    @Test
    fun `everyone with a shared name has their profile read`() = runTest {
        serveDeJongs()
        val lookup = directory.lookup("J. de Jong")
        assertNull(lookup.match)
        assertEquals(setOf("Jasper de Jong", "Jorn de Jong", "Jan-Willem de Jong"), lookup.namesakes.map { it.match.displayName }.toSet())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `the course tells namesakes apart, as the people pages list it`() = runTest {
        serveDeJongs()
        val people = deJongs()
        val ideCalculus = setOf("202001199")
        assertTrue(people.getValue("Jasper de Jong").teaches(ideCalculus, 2026))
        assertFalse(people.getValue("Jorn de Jong").teaches(ideCalculus, 2026))
        assertFalse(people.getValue("Jan-Willem de Jong").teaches(ideCalculus, 2026))
    }

    @Test
    fun `a course can be taught by several namesakes`() = runTest {
        serveDeJongs()
        val people = deJongs()
        val bmtCalculus = setOf("202001195")
        assertTrue(people.getValue("Jasper de Jong").teaches(bmtCalculus, 2026))
        assertTrue(people.getValue("Jorn de Jong").teaches(bmtCalculus, 2026))
    }

    @Test(expected = IOException::class)
    fun `a namesake's unreadable profile fails the lookup, rather than leaving the others`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.requestUrl!!.encodedPath == "/overview") {
                    MockResponse().setBody(Fixtures.read("people/search_j_de_jong.html"))
                } else {
                    MockResponse().setResponseCode(503)
                }
        }
        directory.lookup("J. de Jong")
    }

    @Test
    fun `courses are listed per academic year`() {
        val courses = details("profile_jasper_de_jong.html").courses
        assertEquals(setOf(2025, 2026), courses.keys)
        assertEquals(27, courses.getValue(2026).size)
        assertEquals(39, courses.getValue(2025).size)
    }

    @Test
    fun `a profile without an education tab has no courses`() {
        assertTrue(details("profile_jan_willem_de_jong.html").courses.isEmpty())
    }

    @Test
    fun `links that aren't to a course are skipped`() {
        val html = """<div id="tabpanel-education"><ul>
            <li><a href="https://example.com/?cursuscode=1">No year</a></li>
            <li><a href="not a url?cursuscode=1&collegejaar=2026">Broken</a></li>
            <li><a href="https://example.com/?cursuscode=2&collegejaar=2026">Fine</a></li>
            </ul></div>"""
        assertEquals(mapOf(2026 to setOf("2")), directory.parseDetails(html).courses)
    }

    @Test
    fun `a full first name selects one of several people with the same initial`() {
        val match = directory.match("Yue Li", tiles("search_y_li.html"))
        assertEquals("yue.li", match?.slug)
    }

    @Test
    fun `unknown name matches nobody`() {
        assertNull(directory.match("Q. Nobody", tiles("search_t_van_dijk.html")))
    }

    @Test
    fun `surname alone does not match`() {
        assertNull(directory.match("Dijk", tiles("search_t_van_dijk.html")))
    }

    @Test
    fun `first-name form used for some lecturers matches`() {
        assertEquals("jorn.dejong", directory.match("Jorn de Jong", tiles("search_jorn_de_jong.html"))?.slug)
    }

    @Test
    fun `multi-word surnames and several initials match`() {
        val castor = tile("Castor de Lima Filho, F.J. (Fernando)", "f.castor")
        assertEquals(castor, directory.match("F.J. Castor de Lima Filho", listOf(castor)))
        val bos = tile("Bos, P. van den (Petra)", "p.vandenbos")
        assertEquals(bos, directory.match("P.   van den   Bos", listOf(bos)))
    }

    @Test
    fun `punctuation differences between the two sites still match`() {
        val pronk = tile("Pronk, L.J. (Lucas)", "pronk")
        assertEquals(pronk, directory.match("L.J Pronk", listOf(pronk)))
        val arenas = tile("Arenas - Oude Alink, C.  (Charlotte)", "arenas")
        assertEquals(arenas, directory.match("C. Arenas-Oude Alink", listOf(arenas)))
        val hemmer = tile("Schuurman - Hemmer, H.M.  (Hiske)", "h.m.hemmer")
        assertEquals(hemmer, directory.match("H.M. Schuurman - Hemmer", listOf(hemmer)))
        val spaced = tile("Nogueira Nobre, R. N. N. (Rodrigo)", "r.n.nobre")
        assertEquals(spaced, directory.match("R.N.N. Nogueira Nobre", listOf(spaced)))
    }

    @Test
    fun `timeedit's modifier letter apostrophe matches a plain one`() = runTest {
        assertEquals("Pim van 't Hof", displayName("P. van ʼt Hof", "Hof, P. van 't (Pim)"))
    }

    @Test
    fun `different initials are different people`() {
        val tiles = listOf(tile("Meer, A. van der (Annemieke)"), tile("Meer, A.D. van der (Andries)"))
        assertEquals(tiles[1], directory.match("A.D. van der Meer", tiles))
        assertEquals(tiles[0], directory.match("A. van der Meer", tiles))
    }

    @Test
    fun `matching ignores accents and case`() {
        val tile = tile("Müller, J. (Jörg)", "j.muller")
        assertEquals(tile, directory.match("j. muller", listOf(tile)))
        assertEquals(tile, directory.match("J. Müller", listOf(tile)))
        assertNotNull(directory.match("Jorg Muller", listOf(tile)))
    }

    @Test
    fun `title without a first name is supported`() {
        val tile = tile("Doe, J.")
        assertEquals(tile, directory.match("J. Doe", listOf(tile)))
    }

    @Test
    fun `changed page markup yields no match instead of failing`() {
        assertTrue(directory.parseTiles("<html><body><p>Redesigned</p></body></html>").isEmpty())
    }

    @Test(expected = IOException::class)
    fun `server error is reported, not treated as no match`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        directory.lookup("T. van Dijk")
    }

    @Test
    fun `search url points at the site search`() {
        assertEquals(server.url("/overview?query=Y.%20Li").toString(), directory.searchUrl("Y. Li"))
    }

    @Test
    fun `placeholder roles are not people`() {
        assertTrue(isPlaceholderLecturer("*Unsupervised"))
        assertTrue(isPlaceholderLecturer("*Teaching Assistant"))
        assertFalse(isPlaceholderLecturer("T. van Dijk"))
    }
}
