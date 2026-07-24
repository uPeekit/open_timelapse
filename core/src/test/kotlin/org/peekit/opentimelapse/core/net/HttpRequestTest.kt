package org.peekit.opentimelapse.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpRequestTest {

    @Test
    fun `parses method, path and authorization`() {
        val request = HttpRequest.parse(
            "GET /status?token=x HTTP/1.1",
            listOf("Host: phone:8787", "Authorization: Bearer abc123"),
        )
        assertEquals("GET", request?.method)
        assertEquals("/status?token=x", request?.path)
        assertEquals("Bearer abc123", request?.authorization)
    }

    @Test
    fun `authorization header name is case-insensitive`() {
        val request = HttpRequest.parse("POST /stop HTTP/1.1", listOf("authorization: Bearer t"))
        assertEquals("Bearer t", request?.authorization)
    }

    @Test
    fun `no authorization header yields null authorization`() {
        val request = HttpRequest.parse("GET / HTTP/1.1", listOf("Host: phone"))
        assertNull(request?.authorization)
        assertEquals("/", request?.path)
    }

    @Test
    fun `a malformed request line is rejected`() {
        assertNull(HttpRequest.parse("GARBAGE", emptyList()))
        assertNull(HttpRequest.parse(null, emptyList()))
        assertNull(HttpRequest.parse("", emptyList()))
    }

    @Test
    fun `header values keep their own colons`() {
        val request = HttpRequest.parse(
            "GET / HTTP/1.1",
            listOf("Authorization: Basic dXNlcjpwYXNz"),
        )
        assertEquals("Basic dXNlcjpwYXNz", request?.authorization)
    }
}
