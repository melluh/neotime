package com.melluh.neotime.data

import com.melluh.neotime.timeedit.PeopleDirectory
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration

class LecturerRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private var time = 1_000_000L
    private val cacheFile get() = folder.root.resolve("lecturers.json")

    private fun repository() = LecturerRepository(PeopleDirectory(OkHttpClient(), server.url("/")), cacheFile) { time }

    private fun found() = MockResponse().setBody(
        """<div class="ut-person-tile" data-link="t.vandijk"><h3 class="ut-person-tile__title">Dijk, T. van (Tom)</h3></div>""",
    )

    private fun nobody() = MockResponse().setBody("<html></html>")

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun advance(duration: Duration) {
        time += duration.toMillis()
    }

    @Test
    fun `a match is stored and survives a restart`() = runTest {
        server.enqueue(found())
        repository().load(listOf("T. van Dijk"))
        assertTrue(cacheFile.exists())

        val reopened = repository()
        assertNotNull(reopened.entries.value["T. van Dijk"]?.match)
        reopened.load(listOf("T. van Dijk"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `placeholder roles are never looked up`() = runTest {
        repository().load(listOf("*Unsupervised", "*Teaching Assistant"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `repeated names are looked up once`() = runTest {
        server.enqueue(found())
        repository().load(listOf("T. van Dijk", "T. van Dijk"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `matches expire after a month`() = runTest {
        server.enqueue(found())
        server.enqueue(found())
        val repository = repository()
        repository.load(listOf("T. van Dijk"))

        advance(Duration.ofDays(29))
        repository.load(listOf("T. van Dijk"))
        assertEquals(1, server.requestCount)

        advance(Duration.ofDays(2))
        repository.load(listOf("T. van Dijk"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `misses are remembered but retried sooner`() = runTest {
        server.enqueue(nobody())
        server.enqueue(nobody())
        val repository = repository()
        repository.load(listOf("Y. Li"))
        assertNull(repository.entries.value.getValue("Y. Li").match)

        advance(Duration.ofDays(6))
        repository.load(listOf("Y. Li"))
        assertEquals(1, server.requestCount)

        advance(Duration.ofDays(2))
        repository.load(listOf("Y. Li"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `failures are not remembered`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(found())
        val repository = repository()
        repository.load(listOf("T. van Dijk"))
        assertFalse("T. van Dijk" in repository.entries.value)

        repository.load(listOf("T. van Dijk"))
        assertNotNull(repository.entries.value["T. van Dijk"]?.match)
    }

    @Test
    fun `an expired entry is kept when refreshing it fails`() = runTest {
        server.enqueue(found())
        server.enqueue(MockResponse().setResponseCode(503))
        val repository = repository()
        repository.load(listOf("T. van Dijk"))

        advance(Duration.ofDays(31))
        repository.load(listOf("T. van Dijk"))
        assertNotNull(repository.entries.value["T. van Dijk"]?.match)
    }

    @Test
    fun `a corrupt cache file is ignored`() = runTest {
        cacheFile.writeText("not json")
        assertTrue(repository().entries.value.isEmpty())
    }
}
