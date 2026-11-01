package com.melluh.neotime.timeedit

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot

class MapGeometryTest {
    private val lat = 52.24
    private val metresPerLon = cos(Math.toRadians(lat)) * 111_320.0

    /** A point [x] metres east and [y] metres north of somewhere on campus. */
    private fun at(x: Double, y: Double) = GeoPoint(6.85 + x / metresPerLon, lat + y / 110_540.0)

    private fun ring(vararg points: Pair<Number, Number>) = points.map { (x, y) -> at(x.toDouble(), y.toDouble()) }

    private fun length(rings: Rings) = rings.sumOf { ring ->
        (ring + ring.first()).zipWithNext { a, b -> hypot((b.lon - a.lon) * metresPerLon, (b.lat - a.lat) * 110_540.0) }.sum()
    }

    @Test
    fun `an ordinary outline is left alone`() {
        val outline = listOf(ring(0 to 0, 10 to 0, 10 to 10, 0 to 10, 0 to 0))
        assertEquals(outline, outline.withoutBridges())
    }

    @Test
    fun `two towers come apart`() {
        // Two 10 by 10 towers, 100 m apart, joined by a 1 cm sliver
        val towers = ring(
            0 to 0, 10 to 0, 10 to 5,
            110 to 5, 110 to 0, 120 to 0, 120 to 10, 110 to 10, 110 to 5.01,
            10 to 5.01, 10 to 10, 0 to 10, 0 to 0,
        )
        val parts = listOf(towers).withoutBridges()

        assertEquals(2, parts.size)
        parts.forEach { assertEquals(40.0, length(listOf(it)), 0.1) }
    }

    @Test
    fun `a bridge in the middle of the ring start still leaves one part each`() {
        // As above, but the ring starts out on the far tower
        val towers = ring(
            120 to 0, 120 to 10, 110 to 10, 110 to 5.01,
            10 to 5.01, 10 to 10, 0 to 10, 0 to 0, 10 to 0, 10 to 5,
            110 to 5, 110 to 0, 120 to 0,
        )
        assertEquals(2, listOf(towers).withoutBridges().size)
    }

    @Test
    fun `a wall carries on past the end of a bridge`() {
        // A tower on the right, with a sliver going left along the first 60 m of the 100 m bottom wall
        val outline = ring(0 to 0, 100 to 0, 100 to 20, 60 to 20, 60 to 0.01, 0 to 0.01, 0 to 0)
        val parts = listOf(outline).withoutBridges()
        assertEquals(1, parts.size)
        assertEquals(120.0, length(parts), 0.1)
    }

    @Test
    fun `a fold against a wall doesn't cut the wall`() {
        // Ravelijn floor 1 has one of these: a "hole" that's just a line, lying along the outer wall
        val outer = ring(0 to 0, 50 to 0, 50 to 20, 0 to 20, 0 to 0)
        val fold = ring(20 to 0.05, 22 to 0.05, 20 to 0.05, 20 to 0.05)
        assertEquals(listOf(outer), listOf(outer, fold).withoutBridges())
    }

    @Test
    fun `a spike doesn't cut the wall it runs along`() {
        val spiked = ring(0 to 0, 20 to 0, 20 to 0.05, 22 to 0.05, 20 to 0.05, 50 to 0, 50 to 20, 0 to 20, 0 to 0)
        val parts = listOf(spiked).withoutBridges()
        assertEquals(1, parts.size)
        assertEquals(140.0, length(parts), 0.2)
    }

    @Test
    fun `a real thin wall is kept`() {
        val wall = listOf(ring(0 to 0, 50 to 0, 50 to 0.3, 0 to 0.3, 0 to 0))
        assertEquals(wall, wall.withoutBridges())
    }

    @Test
    fun `no rings stays no rings`() {
        assertTrue(emptyList<List<GeoPoint>>().withoutBridges().isEmpty())
    }

    @Test
    fun `the bridge on a real floor is left out`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Fixtures.read("mazemap/buildings.json")))
        server.start()
        val buildings = MazeMapClient(OkHttpClient(), server.url("/")).buildings()
        server.shutdown()

        // Ravelijn floor 5 has a 31 m sliver in it, so at least twice that goes
        val outline = buildings.flatMap { it.floors }.first { it.id == 4605L }.outline!!
        val removed = length(outline) - length(outline.withoutBridges())
        assertTrue("removed $removed m", removed > 62)
    }
}
