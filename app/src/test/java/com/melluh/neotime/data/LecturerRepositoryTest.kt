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

    private fun profilePage() = MockResponse().setBody(
        """<div class="page-sidebar__panel--specsheet"><a href="mailto:t.vandijk@utwente.nl">t.vandijk@utwente.nl</a></div>""",
    )

    @Test
    fun `a profile is read once and survives a restart`() = runTest {
        server.enqueue(found())
        server.enqueue(profilePage())
        val repository = repository()
        repository.load(listOf("T. van Dijk"))

        assertTrue(repository.loadProfile("T. van Dijk"))
        assertTrue(repository.loadProfile("T. van Dijk"))
        assertEquals(2, server.requestCount)

        val reopened = repository()
        assertEquals("t.vandijk@utwente.nl", reopened.entries.value["T. van Dijk"]?.profile?.details?.email)
    }

    @Test
    fun `a name without a match has no profile to read`() = runTest {
        server.enqueue(nobody())
        val repository = repository()
        repository.load(listOf("Y. Li"))
        assertTrue(repository.loadProfile("Y. Li"))
        assertTrue(repository.loadProfile("Never looked up"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a profile that can't be read is reported and retried`() = runTest {
        server.enqueue(found())
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(profilePage())
        val repository = repository()
        repository.load(listOf("T. van Dijk"))

        assertFalse(repository.loadProfile("T. van Dijk"))
        assertNull(repository.entries.value["T. van Dijk"]?.profile)
        assertTrue(repository.loadProfile("T. van Dijk"))
        assertNotNull(repository.entries.value["T. van Dijk"]?.profile)
    }

    @Test
    fun `an old profile is reread, and stays if that fails`() = runTest {
        server.enqueue(found())
        server.enqueue(profilePage())
        server.enqueue(MockResponse().setResponseCode(503))
        val repository = repository()
        repository.load(listOf("T. van Dijk"))
        repository.loadProfile("T. van Dijk")

        advance(Duration.ofDays(31))
        assertFalse(repository.loadProfile("T. van Dijk"))
        assertEquals(3, server.requestCount)
        assertNotNull(repository.entries.value["T. van Dijk"]?.profile)
    }

    @Test
    fun `refreshing a lookup keeps the profile of the same person`() = runTest {
        server.enqueue(found())
        server.enqueue(profilePage())
        server.enqueue(found())
        val repository = repository()
        repository.load(listOf("T. van Dijk"))
        repository.loadProfile("T. van Dijk")

        advance(Duration.ofDays(31))
        repository.load(listOf("T. van Dijk"))
        assertNotNull(repository.entries.value["T. van Dijk"]?.profile)
    }

    @Test
    fun `refreshing a lookup drops the profile of someone else`() = runTest {
        server.enqueue(found())
        server.enqueue(profilePage())
        server.enqueue(
            MockResponse().setBody(
                """<div class="ut-person-tile" data-link="t.vandijk-1"><h3 class="ut-person-tile__title">Dijk, T. van (Tim)</h3></div>""",
            ),
        )
        val repository = repository()
        repository.load(listOf("T. van Dijk"))
        repository.loadProfile("T. van Dijk")

        advance(Duration.ofDays(31))
        repository.load(listOf("T. van Dijk"))
        assertNull(repository.entries.value["T. van Dijk"]?.profile)
    }

    @Test
    fun `a cache from before profiles still loads`() = runTest {
        cacheFile.writeText("""{"T. van Dijk":{"match":{"profileUrl":"https://example.com/t"},"fetchedAtEpochMs":1000000}}""")
        val entry = repository().entries.value.getValue("T. van Dijk")
        assertEquals("https://example.com/t", entry.match?.profileUrl)
        assertNull(entry.profile)
    }

    @Test
    fun `a match cached without a name is refreshed before it expires`() = runTest {
        cacheFile.writeText("""{"T. van Dijk":{"match":{"profileUrl":"https://old"},"fetchedAtEpochMs":$time}}""")
        server.enqueue(found())
        val repository = repository()
        repository.load(listOf("T. van Dijk"))
        assertEquals(1, server.requestCount)
        assertEquals("Tom van Dijk", repository.entries.value["T. van Dijk"]?.match?.displayName)
    }

    @Test
    fun `a corrupt cache file is ignored`() = runTest {
        cacheFile.writeText("not json")
        assertTrue(repository().entries.value.isEmpty())
    }
}
