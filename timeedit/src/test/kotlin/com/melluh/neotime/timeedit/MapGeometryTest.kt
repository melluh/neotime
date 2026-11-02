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
import kotlin.math.sin

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
    fun `north up keeps north at the top and east on the right`() {
        val projection = MapProjection(listOf(at(0.0, 0.0), at(40.0, 0.0), at(40.0, 20.0), at(0.0, 20.0)))
        assertEquals(40.0, projection.width, 0.01)
        assertEquals(20.0, projection.height, 0.01)
        assertEquals(0.0, projection.y(at(40.0, 20.0)), 0.01)
        assertEquals(40.0, projection.x(at(40.0, 20.0)), 0.01)
    }

    @Test
    fun `the bearing is what points up`() {
        val north = at(0.0, 20.0)
        val east = at(40.0, 0.0)
        val projection = MapProjection(listOf(at(0.0, 0.0), east, at(40.0, 20.0), north), bearing = 90.0)
        assertEquals(20.0, projection.width, 0.01)
        assertEquals(40.0, projection.height, 0.01)
        assertEquals("east is at the top", 0.0, projection.y(east), 0.01)
        assertEquals("north is on the left", 0.0, projection.x(north), 0.01)
    }

    @Test
    fun `turning with a building's walls fits it tightly`() {
        // 40 by 10 metres, with its long walls running 30° east of north
        val along = at(40 * sin(Math.toRadians(30.0)), 40 * cos(Math.toRadians(30.0)))
        val across = at(10 * cos(Math.toRadians(30.0)), -10 * sin(Math.toRadians(30.0)))
        val corners = listOf(at(0.0, 0.0), along, GeoPoint(along.lon + across.lon - 6.85, along.lat + across.lat - lat), across)
        val projection = MapProjection(corners, bearing = 30.0)
        assertEquals(10.0, projection.width, 0.01)
        assertEquals(40.0, projection.height, 0.01)
    }

    @Test
    fun `unprojecting finds the same point back at any bearing`() {
        val points = listOf(at(0.0, 0.0), at(100.0, 30.0), at(-20.0, 80.0))
        listOf(0.0, 30.0, 120.0, 183.0, 300.0).forEach { bearing ->
            val projection = MapProjection(points, bearing)
            points.forEach { point ->
                val back = projection.unproject(projection.x(point), projection.y(point))
                assertEquals(point.lon, back.lon, 1e-9)
                assertEquals(point.lat, back.lat, 1e-9)
            }
        }
    }

    private fun metres(lines: List<WallLine>) =
        lines.sumOf { (a, b) -> hypot((b.lon - a.lon) * metresPerLon, (b.lat - a.lat) * 110_540.0) }

    private fun square(left: Double, size: Double = 10.0) = listOf(
        ring(left to 0, left + size to 0, left + size to size, left to size, left to 0),
    )

    @Test
    fun `shapes that touch lose the line between them`() {
        val joined = joinShapes(listOf(square(0.0), square(10.0)))
        assertEquals("all but the two sides that meet", 60.0, metres(joined.walls), 0.1)
        assertEquals("counted once from each side", 20.0, metres(joined.seams), 0.1)
    }

    @Test
    fun `a sliver between shapes is no wall`() {
        val joined = joinShapes(listOf(square(0.0), square(10.1)))
        assertEquals(60.0, metres(joined.walls), 0.1)
    }

    @Test
    fun `a real wall between shapes stays`() {
        val joined = joinShapes(listOf(square(0.0), square(10.3)))
        assertEquals(80.0, metres(joined.walls), 0.1)
        assertTrue(joined.seams.isEmpty())
    }

    @Test
    fun `shapes drawn the opposite way around join just the same`() {
        val joined = joinShapes(listOf(square(0.0), square(10.0).map { it.reversed() }))
        assertEquals(60.0, metres(joined.walls), 0.1)
    }

    @Test
    fun `only the part where shapes meet goes`() {
        // The second one is half as tall, so half of the first one's side is still a wall
        val joined = joinShapes(listOf(square(0.0), listOf(ring(10 to 0, 15 to 0, 15 to 5, 10 to 5, 10 to 0))))
        assertEquals(50.0, metres(joined.walls), 0.1)
    }

    @Test
    fun `what's inside another shape goes`() {
        val joined = joinShapes(listOf(square(0.0), listOf(ring(2 to 2, 4 to 2, 4 to 4, 2 to 4, 2 to 2))))
        assertEquals("just the outer one", 40.0, metres(joined.walls), 0.1)
    }

    @Test
    fun `a shape doesn't join with itself`() {
        // A U whose two arms are only 10 cm apart
        val u = listOf(ring(0 to 0, 21 to 0, 21 to 10, 10.6 to 10, 10.6 to 1, 10.5 to 1, 10.5 to 10, 0 to 10, 0 to 0))
        val joined = joinShapes(listOf(u))
        assertTrue(joined.seams.isEmpty())
    }

    @Test
    fun `nothing to join is fine`() {
        assertTrue(joinShapes(emptyList()).walls.isEmpty())
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

    @Test
    fun `a point is inside a shape but not in its hole or beside it`() {
        val shape = listOf(ring(0 to 0, 10 to 0, 10 to 10, 0 to 10, 0 to 0), ring(4 to 4, 6 to 4, 6 to 6, 4 to 6, 4 to 4))
        assertTrue(at(2.0, 2.0) in shape)
        assertTrue(at(5.0, 5.0) !in shape)
        assertTrue(at(12.0, 5.0) !in shape)
    }
}
