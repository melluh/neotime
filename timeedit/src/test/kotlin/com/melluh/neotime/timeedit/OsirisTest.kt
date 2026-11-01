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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class OsirisTest {
    private val server = MockWebServer()
    private lateinit var client: OsirisClient
    private val token = OsirisToken("Bearer abc", null)

    @Before
    fun setUp() {
        server.start()
        client = OsirisClient(OkHttpClient(), server.url("/student/osiris"))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun respond(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    @Test
    fun `reads the login url from the 401 challenge`() = runTest {
        respond {
            MockResponse().setResponseCode(401)
                .setBody("""{"Authenticate-Redirect-Url" : "https://auth.example/oauth/authorize?client_id=x" }""")
        }
        assertEquals("https://auth.example/oauth/authorize?client_id=x", client.loginUrl())
    }

    @Test(expected = TimeEditException::class)
    fun `login url fails when the api does not challenge`() = runTest {
        respond { MockResponse().setResponseCode(503) }
        client.loginUrl()
    }

    @Test
    fun `recognises the sign-in callback`() {
        val host = server.url("/?code=abc").toString()
        assertTrue(client.isCallback(host))
        assertEquals("abc", client.callbackCode(host))
        assertNull(client.callbackCode(server.url("/?error=denied").toString()))
        assertFalse(client.isCallback("https://login.microsoftonline.com/?code=abc"))
        assertNull(client.callbackCode("https://login.microsoftonline.com/?code=abc"))
    }

    @Test
    fun `reads the code from the fragment, where the server puts it`() {
        assertEquals("abc", client.callbackCode(server.url("/").toString() + "#code=abc"))
        assertEquals("a&b", client.callbackCode(server.url("/").toString() + "#iss=x&code=a%26b"))
        assertNull(client.callbackCode(server.url("/").toString() + "#error=denied"))
    }

    @Test
    fun `the saml endpoint on the same host is not the callback`() {
        assertFalse(client.isCallback(server.url("/osirissaml/saml2/acs/osiris").toString()))
        assertNull(client.callbackCode(server.url("/osirissaml/saml2/acs/osiris?code=abc").toString()))
    }

    @Test
    fun `exchanges a code like the web client does`() = runTest {
        respond {
            MockResponse().setBody("""{"access_token":"abc","token_type":"bearer","expires_in":3600,"scope":"read"}""")
        }
        val now = Instant.parse("2026-11-01T12:00:00Z")

        val result = client.exchangeCode("the-code", now)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/student/osiris/token", request.path)
        assertEquals("""{"code":"the-code","redirect_uri":""}""", request.body.readUtf8())
        assertEquals("Bearer abc", result.authorization)
        assertEquals(Instant.parse("2026-11-01T12:59:00Z"), result.expiresAt)
    }

    @Test
    fun `accepts expires_in as a string`() = runTest {
        respond { MockResponse().setBody("""{"access_token":"abc","expires_in":"60"}""") }
        val now = Instant.parse("2026-11-01T12:00:00Z")
        assertEquals(now, client.exchangeCode("c", now).expiresAt)
    }

    @Test(expected = TimeEditException::class)
    fun `a rejected code fails`() = runTest {
        respond {
            MockResponse().setResponseCode(400)
                .setBody("""{"error":"invalid_grant","error_description":"Invalid authorization code: x"}""")
        }
        client.exchangeCode("x")
    }

    @Test
    fun `token expiry`() {
        val at = Instant.parse("2026-11-01T12:00:00Z")
        assertFalse(OsirisToken("a", null).isExpired(at))
        assertFalse(OsirisToken("a", at.plusSeconds(1)).isExpired(at))
        assertTrue(OsirisToken("a", at).isExpired(at))
    }

    @Test
    fun `parses registrations and sends the token`() = runTest {
        respond {
            MockResponse().setBody(
                """{"items":[
                    {"cursus":"202001212","cursus_korte_naam":"Calculus 1 for AT","collegejaar":2026,"blok":"1A","unknown":1},
                    {"cursus":"202500342","cursus_lange_naam":"Introduction to Programming","collegejaar":"2026"},
                    {"cursus_korte_naam":"No code"}
                ],"hasMore":false}""",
            )
        }

        val result = client.registrations(token)

        assertEquals(
            listOf(
                OsirisRegistration("202001212", "Calculus 1 for AT", "2026", "1A"),
                OsirisRegistration("202500342", "Introduction to Programming", "2026", null),
            ),
            result,
        )
        val request = server.takeRequest()
        assertEquals("Bearer abc", request.getHeader("Authorization"))
        assertEquals("/student/osiris/student/inschrijvingen/cursussen?toon_historie=N&limit=100", request.path)
    }

    @Test
    fun `follows pages until there are no more`() = runTest {
        respond { request ->
            val offset = request.requestUrl?.queryParameter("offset")
            MockResponse().setBody(
                if (offset == null) """{"items":[{"cursus":"1"},{"cursus":"2"}],"hasMore":true}"""
                else """{"items":[{"cursus":"${offset.toInt() + 1}"}],"hasMore":false}""",
            )
        }
        assertEquals(listOf("1", "2", "3"), client.registrations(token).map { it.code })
        assertEquals(2, server.requestCount)
    }

    @Test(expected = NotLoggedInException::class)
    fun `a dead token is reported`() = runTest {
        respond { MockResponse().setResponseCode(401).setBody("{}") }
        client.registrations(token)
    }

    @Test
    fun `matches a course by code`() {
        val refs = listOf(
            ObjectRef("1.5", "Calculus 1 for TN, 202001215, 2026, 1A"),
            ObjectRef("2.5", "Calculus 1 for AT, 202001212, 2026, 1A"),
        )
        assertEquals("2.5", OsirisRegistration("202001212", "Calculus", "2026", "1A").matchIn(refs)?.id)
    }

    @Test
    fun `never matches another academic year`() {
        val refs = listOf(ObjectRef("1.5", "Calculus 1 for AT, 202001212, 2025, 1A"))
        assertNull(OsirisRegistration("202001212", "Calculus", "2026", "1A").matchIn(refs))
    }

    @Test
    fun `prefers the object for the registered block`() {
        val refs = listOf(
            ObjectRef("1.5", "Calculus 1 for AT, 202001212, 2026, 1A"),
            ObjectRef("2.5", "Calculus 1 for AT, 202001212, 2026, 2A"),
        )
        assertEquals("2.5", OsirisRegistration("202001212", "Calculus", "2026", "2A").matchIn(refs)?.id)
    }

    @Test
    fun `still matches when the block format differs`() {
        val refs = listOf(ObjectRef("1.5", "Calculus 1 for AT, 202001212, 2026, 1A"))
        assertEquals("1.5", OsirisRegistration("202001212", "Calculus", "2026", "1").matchIn(refs)?.id)
    }

    @Test
    fun `matches names without a year and the dotted name style`() {
        val refs = listOf(ObjectRef("1.5", "Introduction to Programming. 202500342"))
        assertEquals("1.5", OsirisRegistration("202500342", "Programming", "2026").matchIn(refs)?.id)
    }

    @Test
    fun `does not match a code that is only a prefix`() {
        val refs = listOf(ObjectRef("1.5", "Other course, 2020012129, 2026, 1A"))
        assertNull(OsirisRegistration("202001212", "Calculus", "2026").matchIn(refs))
    }
}
