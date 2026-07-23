package org.peekit.opentimelapse.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlAuthTest {

    private val token = "s3cr3t-abcdefgh"

    @Test
    fun `the shell page is public so a token can be entered`() {
        assertEquals(Access.ALLOW_PUBLIC, ControlAuth.evaluate(token, "GET", "/", presentedToken = null))
    }

    @Test
    fun `every data route needs the token, preview included`() {
        for (route in listOf("/status", "/preview", "/events")) {
            assertEquals(
                Access.UNAUTHORIZED,
                ControlAuth.evaluate(token, "GET", route, presentedToken = null),
                "$route must not be readable without the token",
            )
            assertEquals(
                Access.ALLOW,
                ControlAuth.evaluate(token, "GET", route, presentedToken = token),
            )
        }
    }

    @Test
    fun `control routes need the token`() {
        assertEquals(Access.UNAUTHORIZED, ControlAuth.evaluate(token, "POST", "/stop", "wrong"))
        assertEquals(Access.ALLOW, ControlAuth.evaluate(token, "POST", "/stop", token))
        assertEquals(Access.ALLOW, ControlAuth.evaluate(token, "POST", "/start", token))
    }

    @Test
    fun `a token in the query string authorizes a browser GET`() {
        val presented = ControlAuth.presentedToken(authorizationHeader = null, path = "/preview?token=$token")
        assertEquals(Access.ALLOW, ControlAuth.evaluate(token, "GET", "/preview?token=$token", presented))
    }

    @Test
    fun `unknown path is not found, wrong method is bad method`() {
        assertEquals(Access.NOT_FOUND, ControlAuth.evaluate(token, "GET", "/nope", token))
        // /stop exists, but only for POST.
        assertEquals(Access.BAD_METHOD, ControlAuth.evaluate(token, "GET", "/stop", token))
    }

    @Test
    fun `an empty configured token never matches - the server should not even run`() {
        assertFalse(ControlAuth.tokenMatches("", "anything"))
        assertFalse(ControlAuth.tokenMatches("", ""))
        assertEquals(Access.UNAUTHORIZED, ControlAuth.evaluate("", "GET", "/status", ""))
    }

    @Test
    fun `token compare rejects near-misses and length differences`() {
        assertTrue(ControlAuth.tokenMatches(token, token))
        assertFalse(ControlAuth.tokenMatches(token, token.dropLast(1)))
        assertFalse(ControlAuth.tokenMatches(token, token + "x"))
        assertFalse(ControlAuth.tokenMatches(token, token.dropLast(1) + "X"))
        assertFalse(ControlAuth.tokenMatches(token, null))
    }

    @Test
    fun `bearer header is read and wins over the query string`() {
        assertEquals(token, ControlAuth.presentedToken("Bearer $token", "/status"))
        assertEquals(token, ControlAuth.presentedToken("bearer $token", "/status"))
        // Header present but different from query: header is authoritative.
        assertEquals(token, ControlAuth.presentedToken("Bearer $token", "/status?token=other"))
    }

    @Test
    fun `no token anywhere yields null`() {
        assertNull(ControlAuth.presentedToken(authorizationHeader = null, path = "/status"))
        assertNull(ControlAuth.presentedToken(authorizationHeader = "", path = "/status"))
        assertNull(ControlAuth.presentedToken(authorizationHeader = "Bearer ", path = "/status"))
    }

    @Test
    fun `routes match ignoring the query string`() {
        assertEquals(Route.PREVIEW, Route.match("GET", "/preview?token=x&w=320"))
        assertEquals(Route.STATUS, Route.match("GET", "/status"))
        assertNull(Route.match("DELETE", "/status"))
    }
}
