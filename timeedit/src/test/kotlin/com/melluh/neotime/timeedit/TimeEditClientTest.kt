package com.melluh.neotime.timeedit

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class TimeEditClientTest {
    private val server = MockWebServer()
    private lateinit var client: TimeEditClient

    @Before
    fun setUp() {
        server.start()
        client = TimeEditClient(OkHttpClient(), server.url("/nl_utwente/web"))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun respond(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    @Test(expected = NotLoggedInException::class)
    fun `login gate throws NotLoggedIn`() = runTest {
        respond { MockResponse().setResponseCode(412).setBody(Fixtures.read("gate.html")) }
        client.schedule(Area.STUDENT, 16, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), listOf("12335.6"))
    }

    @Test
    fun `schedule request has the expected parameters`() = runTest {
        respond { MockResponse().setBody(Fixtures.read("student/ri_sid16_en.json")) }
        val events = client.schedule(Area.STUDENT, 16, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 22), listOf("12335.6", "22425.5"))
        assertEquals(44, events.size)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/nl_utwente/web/student/ri.json", url.encodedPath)
        assertEquals("16", url.queryParameter("sid"))
        assertEquals("20261026.x,20261122.x", url.queryParameter("p"))
        assertEquals("12335.6,22425.5", url.queryParameter("objects"))
        assertEquals("en_US", url.queryParameter("l"))
    }

    @Test
    fun `truncated ranges are split and deduplicated`() = runTest {
        val event = """{"id":"%s","startdate":"%s","starttime":"09:00","enddate":"%s","endtime":"10:00","columns":["C. 123456"]}"""
        respond { req ->
            val (from, to) = req.requestUrl!!.queryParameter("p")!!.split(',').map { it.removeSuffix(".x") }
            val days = LocalDate.parse(to, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toEpochDay() -
                LocalDate.parse(from, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toEpochDay()
            // Pretend anything longer than 10 days hits the limit; one event shared at every boundary
            val count = if (days > 10) 2 else 1
            val body = """{"columnheaders":["Course"],"info":{"reservationlimit":2,"reservationcount":$count},
                "reservations":[${event.format(from, "01/11/2026", "01/11/2026")},${event.format("shared", "02/11/2026", "02/11/2026")}]}"""
            MockResponse().setBody(body)
        }
        val events = client.schedule(Area.PUBLIC, 10, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), listOf("1.5"))
        assertTrue(server.requestCount > 1)
        assertEquals(events.map { it.id }.distinct(), events.map { it.id })
        assertEquals(1, events.count { it.id == "shared" })
    }

    @Test
    fun `student search parses JSON objects`() = runTest {
        respond { MockResponse().setBody(Fixtures.read("student/search_sets.json")) }
        val results = client.search(Area.STUDENT, 16, ObjectType.STUDENT_SET, "TCS MOD02")
        assertEquals(ObjectRef("12335.6", "TCS MOD02 Group 01"), results.first())
        assertEquals("objects.json", server.takeRequest().requestUrl!!.pathSegments.last())
    }

    @Test
    fun `student search without matches returns no results`() = runTest {
        respond { MockResponse().setBody("No search results") }
        assertEquals(emptyList<ObjectRef>(), client.search(Area.STUDENT, 16, ObjectType.STUDENT_SET, "nothing"))
    }

    @Test
    fun `room list without matches returns no rooms`() = runTest {
        respond { MockResponse().setBody("No search results") }
        assertEquals(emptyList<Room>(), client.rooms(12, "Nowhere"))
    }

    @Test(expected = SerializationException::class)
    fun `other non-JSON search responses still fail`() = runTest {
        respond { MockResponse().setBody("Internal error") }
        client.search(Area.STUDENT, 16, ObjectType.STUDENT_SET, "anything")
    }

    @Test
    fun `public search parses HTML results`() = runTest {
        respond { MockResponse().setBody(Fixtures.read("public/search_courses.html")) }
        val results = client.search(Area.PUBLIC, 3, ObjectType.COURSE, "calculus")
        assertEquals(ObjectRef("22425.5", "Calculus 1 for AT, 202600368, 1A, 2026"), results.first())
        assertTrue(results.all { it.type == ObjectType.COURSE })
    }

    @Test
    fun `token exchange posts the token form-encoded to the student page`() = runTest {
        respond { MockResponse().setResponseCode(200) }
        assertTrue(client.exchangeToken("a.b.c"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/nl_utwente/web/student/", req.requestUrl!!.encodedPath)
        assertEquals("teauthtoken=a.b.c", req.body.readUtf8())
    }
}
