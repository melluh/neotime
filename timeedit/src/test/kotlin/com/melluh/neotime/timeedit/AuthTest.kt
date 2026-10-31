package com.melluh.neotime.timeedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

class AuthTest {
    private fun token(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"HS256","typ":"JWT"}""", payload, "sig")
            .joinToString(".") { enc.encodeToString(it.toByteArray()) }
    }

    @Test
    fun `claims are decoded`() {
        val claims = Jwt.claims(token("""{"username":"s0000000","email":"a@b.nl","iat":1793462786,"exp":1793549186,"scopes":["TE_VIEWER::user"]}"""))
        assertEquals("s0000000", claims.username)
        assertEquals("a@b.nl", claims.email)
        assertEquals(Instant.ofEpochSecond(1793549186), claims.expiresAt)
        assertFalse(claims.isExpired(Instant.ofEpochSecond(1793549185)))
        assertTrue(claims.isExpired(Instant.ofEpochSecond(1793549186)))
    }

    @Test
    fun `account is read from logged-in pages only`() {
        assertEquals("Test Student" to "s0000000", AccountParser.fromPage(Fixtures.read("student/landing.html")))
        assertNull(AccountParser.fromPage(Fixtures.read("public/landing.html")))
    }

    @Test
    fun `login gate is recognised`() {
        assertTrue(TimeEditClient.isLoginGate(412, Fixtures.read("gate.html")))
        assertTrue(TimeEditClient.isLoginGate(200, Fixtures.read("gate.html")))
        assertFalse(TimeEditClient.isLoginGate(200, Fixtures.read("student/landing.html")))
    }
}
