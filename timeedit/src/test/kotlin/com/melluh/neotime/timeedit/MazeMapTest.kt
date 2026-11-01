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

class MazeMapTest {
    private val server = MockWebServer()
    private lateinit var client: MazeMapClient

    @Before
    fun setUp() {
        server.start()
        client = MazeMapClient(OkHttpClient(), server.url("/"))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun poi(extra: String) = """{"pois": [{"poiId": 1, $extra}], "limit": 2000}"""

    @Test
    fun `buildings are sorted by name with floors by z`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("mazemap/buildings.json")))
        val buildings = client.buildings()

        assertEquals(listOf("Ravelijn", "Zilverling"), buildings.map { it.name })
        // Zilverling's basement is called 0, one level below floor 1 at z = -1
        val zilverling = buildings[1]
        assertEquals(listOf("0", "1", "2", "3", "4", "5"), zilverling.floors.map { it.name })
        assertEquals(-1.0, zilverling.floors.first().z, 0.0)
        assertEquals("1", zilverling.defaultFloor?.name)
    }

    @Test
    fun `floor outlines are read as rings`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("mazemap/buildings.json")))
        val floor = client.buildings().flatMap { it.floors }.first { it.id == 4601L }
        // The outer wall, then a hole in the floor
        assertEquals(listOf(97, 4), floor.outline!!.map { it.size })
        val corner = floor.outline.first().first()
        assertEquals(6.8, corner.lon, 0.1)
        assertEquals(52.2, corner.lat, 0.1)
    }

    @Test
    fun `multi polygon outlines keep every part`() {
        val body = """{"buildings": [{"id": 1, "name": "B", "floors": [{"id": 2, "name": "1", "z": 1.0, "outline":
            {"type": "MultiPolygon", "coordinates": [[[[0, 0], [1, 0], [1, 1], [0, 0]]], [[[5, 5], [6, 5], [6, 6], [5, 5]]]]}}]}]}"""
        assertEquals(2, client.parseBuildings(body).single().floors.single().outline!!.size)
    }

    @Test
    fun `default floor falls back to the only floors there are`() {
        val body = """{"buildings": [{"id": 1, "name": "B", "floors": [{"id": 2, "name": "-1", "z": -1.0}]}]}"""
        assertEquals(2L, client.parseBuildings(body).single().defaultFloor?.id)
        assertNull(MapBuilding(1, "Empty", emptyList()).defaultFloor)
    }

    @Test
    fun `features of a real floor parse`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("mazemap/pois_floor_4601.json")))
        val features = client.floorFeatures(4601)

        assertEquals(79, features.size)
        assertEquals(5, features.count { it.rings == null }) // the Point features
        // One room on this floor has holes
        assertEquals(4, features.mapNotNull { it.rings?.size }.max())
        val lecture = features.single { it.title == "Collegezaal 1501" }
        assertEquals("room", lecture.kind)
        assertEquals(listOf("Lecture Hall"), lecture.typeNames)
        assertNotNull(lecture.labelPoint)
        assertTrue(features.any { it.title == null && it.identifier == null })
    }

    private fun feature(title: String?, kind: String = "room", vararg types: String) =
        MapFeature(id = 1, kind = kind, title = title, typeNames = types.toList())

    @Test
    fun `places you'd go to are destinations`() {
        assertTrue(feature("Collegezaal 4237", "room", "Lecture Hall").isDestination)
        assertTrue(feature("Sporthal 1").isDestination) // most rooms have no type at all
        assertTrue(feature("4244").isDestination)
        assertTrue(feature("ZH301").isDestination)
        assertTrue(feature("Servicedesk", "room", "Reception").isDestination)
    }

    @Test
    fun `what you only pass through isn't`() {
        assertFalse(feature("4000VB", "room", "Hallway").isDestination)
        assertFalse(feature("1000VF", "room", "Traffic Area", "Hallway").isDestination)
        assertFalse(feature("4251", "room", "Technical Room").isDestination)
        assertFalse(feature("1274", "room", "Technische ruimte").isDestination)
        assertFalse(feature("4221", "room", "Toilet - Male").isDestination)
        assertFalse(feature("116B", "room", "Toilet").isDestination)
        assertFalse(feature("1000VC", "room", "Elevators").isDestination)
        assertFalse(feature("Lockers SP 2001-2036", "room", "Lockers").isDestination)
        assertFalse(feature("1234", "stairs").isDestination)
        assertFalse(feature("124", "circulation_room").isDestination)
        assertFalse(feature("VR260_M32", "desk").isDestination)
        assertFalse(feature(null).isDestination)
    }

    @Test
    fun `lecture halls are labelled without the Collegezaal in front`() {
        assertEquals("WA1", feature("Collegezaal WA1", "room", "Lecture Hall").label)
        assertEquals("2234", feature("Collegezaal 2234", "room", "Lecture Hall").label)
        assertEquals("Collegezaal 2234", feature("Collegezaal 2234", "room", "Lecture Hall").title)
    }

    @Test
    fun `other labels stay as they are`() {
        assertEquals("Vergaderzaal 1315", feature("Vergaderzaal 1315", "room", "Lecture Hall").label)
        assertEquals("4244", feature("4244").label)
        assertEquals("Collegezaal", feature("Collegezaal").label)
    }

    @Test
    fun `only destinations get a label`() {
        assertNull(feature("4000VB", "room", "Hallway").label)
        assertNull(feature(null).label)
    }

    @Test
    fun `corridors with no type are recognised by their code`() {
        assertFalse(feature("100VC").isDestination)
        assertFalse(feature("C000VE").isDestination)
        assertFalse(feature("1500VA").isDestination)
        assertTrue(feature("VR275").isDestination) // the Vrijhof, not a corridor
    }

    @Test
    fun `a real floor keeps its rooms and loses the rest`() {
        val features = client.parseFeatures(Fixtures.read("mazemap/pois_floor_4601.json"))
        val labels = features.filter { it.isDestination }.mapNotNull { it.title }
        assertTrue("Collegezaal 1501" in labels)
        assertTrue("1202" in labels)
        assertFalse("1000VA" in labels)
        assertFalse(features.filter { "Hallway" in it.typeNames }.any { it.isDestination })
        assertTrue(labels.size < features.size)
    }

    @Test
    fun `unknown kinds and missing fields are fine`() {
        val feature = client.parseFeatures(poi(""""kind": "teleporter", "title": " ", "unknownField": {"a": 1}""")).single()
        assertEquals("teleporter", feature.kind)
        assertNull(feature.title)
        assertNull(feature.rings)
        assertNull(feature.labelPoint)
    }

    @Test
    fun `point geometry has a label position but no shape`() {
        val feature = client.parseFeatures(poi(""""geometry": {"type": "Point", "coordinates": [6.8, 52.2]}""")).single()
        assertNull(feature.rings)
        assertEquals(GeoPoint(6.8, 52.2), feature.labelPoint)
    }

    @Test
    fun `unsupported geometry is skipped`() {
        val feature = client.parseFeatures(poi(""""geometry": {"type": "LineString", "coordinates": [[0, 0], [1, 1]]}""")).single()
        assertNull(feature.rings)
    }

    @Test
    fun `deleted features are dropped`() {
        assertTrue(client.parseFeatures(poi(""""deleted": true""")).isEmpty())
    }

    @Test(expected = MazeMapException::class)
    fun `a full page is refused rather than shown as a partial floor`() {
        client.parseFeatures("""{"pois": [{"poiId": 1}, {"poiId": 2}], "limit": 2}""")
    }

    @Test
    fun `requests have the expected parameters`() = runTest {
        server.enqueue(MockResponse().setBody("""{"buildings": []}"""))
        server.enqueue(MockResponse().setBody("""{"pois": []}"""))
        client.buildings()
        client.floorFeatures(4601)

        val buildings = server.takeRequest()
        assertEquals("/api/buildings/", buildings.requestUrl!!.encodedPath)
        assertEquals("171", buildings.requestUrl!!.queryParameter("campusid"))
        assertEquals("4326", buildings.requestUrl!!.queryParameter("srid"))
        assertEquals("neotime", buildings.getHeader("User-Agent"))

        val pois = server.takeRequest()
        assertEquals("/api/pois/", pois.requestUrl!!.encodedPath)
        assertEquals("4601", pois.requestUrl!!.queryParameter("floorid"))
        assertEquals("4326", pois.requestUrl!!.queryParameter("srid"))
        assertEquals("neotime", pois.getHeader("User-Agent"))
    }

    @Test(expected = MazeMapException::class)
    fun `http errors surface as MazeMapException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        client.buildings()
    }

    @Test(expected = MazeMapException::class)
    fun `malformed json surfaces as MazeMapException`() {
        client.parseBuildings("<html>not json</html>")
    }

    @Test(expected = MazeMapException::class)
    fun `malformed coordinates surface as MazeMapException`() {
        client.parseFeatures(poi(""""geometry": {"type": "Polygon", "coordinates": [[[1]]]}"""))
    }
}
