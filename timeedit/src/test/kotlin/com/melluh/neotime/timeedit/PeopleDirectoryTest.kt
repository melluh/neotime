package com.melluh.neotime.timeedit

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

    @Test
    fun `lookup returns the profile and photo of the one match`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("people/search_t_van_dijk.html")))
        val match = directory.lookup("T. van Dijk")
        assertEquals(server.url("/t.vandijk").toString(), match!!.profileUrl)
        assertTrue(match.photoUrl!!.startsWith("https://utwente.becdn.net/"))
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
        val match = directory.lookup("Y. van Dijk")
        assertEquals(server.url("/y.vandijk").toString(), match!!.profileUrl)
        assertNull(match.photoUrl)
    }

    @Test
    fun `ambiguous name matches nobody`() = runTest {
        val tiles = tiles("search_y_li.html")
        assertTrue(tiles.count { it.title.startsWith("Li, Y. (") } > 1)
        assertNull(directory.match("Y. Li", tiles))
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
