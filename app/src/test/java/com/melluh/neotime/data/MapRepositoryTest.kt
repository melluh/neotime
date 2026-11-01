package com.melluh.neotime.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.melluh.neotime.timeedit.MapOverrides
import com.melluh.neotime.timeedit.MazeMapClient
import com.melluh.neotime.timeedit.RoomLocation
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration

class MapRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private var time = 1_000_000L
    private val location = RoomLocation(7, "HCT1001", 1, 2)

    private val dataStore by lazy { PreferenceDataStoreFactory.create { folder.root.resolve("test.preferences_pb") } }

    private fun repository(overrides: String = "{}") = MapRepository(
        MazeMapClient(OkHttpClient(), server.url("/")),
        dataStore,
        folder.root.resolve("mazemap"),
        readOverrides = { overrides },
    ) { time }

    private fun buildings() = MockResponse().setBody("""{"buildings": [{"id": 1, "name": "Ravelijn"}]}""")

    private fun found(identifier: String = "HCT1001") = MockResponse().setBody(
        """{"pois": [{"poiId": 7, "identifier": "$identifier", "kind": "room", "buildingId": 1, "floorId": 2}]}""",
    )

    private fun nothing() = MockResponse().setBody("""{"pois": []}""")

    private fun advance(duration: Duration) {
        time += duration.toMillis()
    }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private suspend fun bearing(repository: MapRepository) =
        (repository.buildings() as MapResult.Ok).value.single().bearing

    @Test
    fun `new overrides count for buildings that are already cached`() = runTest {
        server.enqueue(buildings())
        assertEquals(30.0, bearing(repository("""{"buildings": {"1": {"bearing": 30}}}""")), 0.0)
        assertEquals(120.0, bearing(repository("""{"buildings": {"1": {"bearing": 120}}}""")), 0.0)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the overrides that ship with the app can be read`() {
        val overrides = MapOverrides.parse(File("src/main/assets/map-overrides.json").readText())
        assertTrue(overrides.buildings.isNotEmpty())
        assertTrue(overrides.buildings.values.mapNotNull { it.bearing }.all { it in 0.0..360.0 })
    }

    @Test
    fun `candidates are tried in order`() = runTest {
        server.enqueue(nothing())
        server.enqueue(found("HT1001"))
        assertEquals(MapResult.Ok(location.copy(identifier = "HT1001")), repository().locate("HT 1001"))
        assertEquals("HCT1001", server.takeRequest().requestUrl!!.queryParameter("identifier"))
        assertEquals("HT1001", server.takeRequest().requestUrl!!.queryParameter("identifier"))
    }

    @Test
    fun `an identifier from TimeEdit is used as is`() = runTest {
        server.enqueue(found())
        assertEquals(MapResult.Ok(location), repository().locate("Some name", "HCT1001"))
        assertEquals(1, server.requestCount)
        assertEquals("HCT1001", server.takeRequest().requestUrl!!.queryParameter("identifier"))
    }

    @Test
    fun `a location survives a restart`() = runTest {
        server.enqueue(found())
        repository().locate("HT 1001")
        assertEquals(MapResult.Ok(location), repository().locate("HT 1001"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a location is checked again after 30 days`() = runTest {
        server.enqueue(found())
        val repository = repository()
        repository.locate("HT 1001")
        advance(Duration.ofDays(29))
        repository.locate("HT 1001")
        assertEquals(1, server.requestCount)
        advance(Duration.ofDays(2))
        server.enqueue(found())
        repository.locate("HT 1001")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a room that isn't on the map is checked again after 7 days`() = runTest {
        server.enqueue(nothing())
        val repository = repository()
        assertEquals(MapResult.Ok(null), repository.locate("VR 999"))
        advance(Duration.ofDays(6))
        assertEquals(MapResult.Ok(null), repository.locate("VR 999"))
        assertEquals(1, server.requestCount)
        advance(Duration.ofDays(2))
        server.enqueue(found())
        repository.locate("VR 999")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an old location is served when MazeMap fails`() = runTest {
        server.enqueue(found())
        val repository = repository()
        repository.locate("HT 1001")
        advance(Duration.ofDays(31))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(MapResult.Ok(location, stale = true), repository.locate("HT 1001"))
    }

    @Test
    fun `a failed lookup isn't remembered as a missing room`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val repository = repository()
        assertTrue(repository.locate("VR 999") is MapResult.Failed)
        server.enqueue(found())
        repository.locate("VR 999")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `what isn't a room asks nothing`() = runTest {
        assertEquals(MapResult.Ok(null), repository().locate("**No Room Required"))
        assertEquals(0, server.requestCount)
    }
}
