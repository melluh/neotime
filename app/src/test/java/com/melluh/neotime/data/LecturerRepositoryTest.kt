package com.melluh.neotime.data

import com.melluh.neotime.timeedit.Event
import com.melluh.neotime.timeedit.PeopleDirectory
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.LocalDate

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

    private fun namesakes() = MockResponse().setBody(
        """<div class="ut-person-tile" data-link="jasper"><h3 class="ut-person-tile__title">Jong, J. de (Jasper)</h3></div>
           <div class="ut-person-tile" data-link="jorn"><h3 class="ut-person-tile__title">Jong, J. de (Jorn)</h3></div>""",
    )

    private fun teaching(email: String, vararg courses: Pair<Int, String>) = MockResponse().setBody(
        """<div class="page-sidebar__panel--specsheet"><a href="mailto:$email">$email</a></div>
           <div id="tabpanel-education"><ul>""" +
            courses.joinToString("") { (year, code) -> """<li><a href="https://osiris.example/c?cursuscode=$code&collegejaar=$year">x</a></li>""" } +
            "</ul></div>",
    )

    /** Jasper teaches 111 and 333, Jorn teaches 222 and 333, and last year only Jorn taught 111. */
    private suspend fun jDeJong(): LecturerEntry {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                "/overview" -> namesakes()
                "/jasper" -> teaching("jasper@utwente.nl", 2026 to "111111", 2026 to "333333")
                else -> teaching("jorn@utwente.nl", 2026 to "222222", 2026 to "333333", 2025 to "111111")
            }
        }
        val repository = repository()
        repository.load(listOf("J. de Jong"))
        return repository.entries.value.getValue("J. de Jong")
    }

    private fun event(course: String, date: LocalDate = LocalDate.of(2026, 12, 17)) =
        Event("1", date.atStartOfDay(), date.atStartOfDay(), course)

    @Test
    fun `a shared name is whoever teaches the event's course`() = runTest {
        val entry = jDeJong()
        assertNull(entry.match)
        assertEquals("Jasper de Jong", entry.on(event("Calculus. 111111")).match?.displayName)
        assertEquals("Jorn de Jong", entry.on(event("Calculus. 222222")).match?.displayName)
    }

    @Test
    fun `a namesake's profile needs no further reading`() = runTest {
        val entry = jDeJong().on(event("Calculus. 111111"))
        assertEquals("jasper@utwente.nl", entry.profile?.details?.email)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `any of a combined event's courses tells namesakes apart`() = runTest {
        assertEquals("Jorn de Jong", jDeJong().on(event("Other. 999999, Calculus. 222222")).match?.displayName)
    }

    @Test
    fun `a course taught by several namesakes or none of them stays unresolved`() = runTest {
        val entry = jDeJong()
        assertNull(entry.on(event("Calculus. 333333")).match)
        assertNull(entry.on(event("Unlisted. 999999")).match)
        assertNull(entry.on(event("No code")).match)
    }

    @Test
    fun `only the event's own academic year counts`() = runTest {
        val lastYear = LocalDate.of(2026, 3, 1)
        assertEquals("Jorn de Jong", jDeJong().on(event("Calculus. 111111", lastYear)).match?.displayName)
    }

    @Test
    fun `namesakes survive a restart`() = runTest {
        jDeJong()
        val entry = repository().entries.value.getValue("J. de Jong")
        assertEquals("Jasper de Jong", entry.on(event("Calculus. 111111")).match?.displayName)
    }

    @Test
    fun `someone with their name to themselves is the same on every event`() = runTest {
        server.enqueue(found())
        val repository = repository()
        repository.load(listOf("T. van Dijk"))
        val entry = repository.entries.value.getValue("T. van Dijk")
        assertEquals(entry, entry.on(event("Calculus. 111111")))
    }

    @Test
    fun `namesakes are looked up again sooner, as course lists fill up`() = runTest {
        jDeJong()
        val repository = repository()
        val before = server.requestCount
        advance(Duration.ofDays(6))
        repository.load(listOf("J. de Jong"))
        assertEquals(before, server.requestCount)

        advance(Duration.ofDays(2))
        repository.load(listOf("J. de Jong"))
        assertEquals(before + 3, server.requestCount)
    }

    @Test
    fun `a miss cached before namesakes were kept is refreshed before it expires`() = runTest {
        cacheFile.writeText("""{"J. de Jong":{"fetchedAtEpochMs":$time}}""")
        val entry = jDeJong()
        assertEquals("Jasper de Jong", entry.on(event("Calculus. 111111")).match?.displayName)
    }

    @Test
    fun `a corrupt cache file is ignored`() = runTest {
        cacheFile.writeText("not json")
        assertTrue(repository().entries.value.isEmpty())
    }
}
