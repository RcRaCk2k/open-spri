package de.openspri.rest.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RateLimitTest {

    private var now = 0L

    @Test
    fun `bucket allows the burst, then refills at capacity per minute`() {
        val limiter = RateLimiter(60) { now }
        repeat(60) { assertEquals(0, limiter.acquire("a")) }
        assertEquals(1, limiter.acquire("a"))
        assertEquals(0, limiter.acquire("b"), "other keys have their own bucket")
        now += 1_000_000_000L            // one second refills one request
        assertEquals(0, limiter.acquire("a"))
        assertEquals(1, limiter.acquire("a"))
    }

    @Test
    fun `retry-after counts the seconds until the next request is allowed`() {
        val limiter = RateLimiter(6) { now }  // one request every 10 s
        repeat(6) { limiter.acquire("a") }
        assertEquals(10, limiter.acquire("a"))
        now += 4_000_000_000L
        assertEquals(6, limiter.acquire("a"))
    }

    @Test
    fun `login attempts are limited per address and answered with 429 and Retry-After`() {
        val filter = RateLimitFilter(RateLimitProperties(requestsPerMinute = 100, loginPerMinute = 3))
        fun login(address: String) = MockHttpServletResponse().also { response ->
            val request = MockHttpServletRequest("POST", "/api/v1/auth/login").apply { remoteAddr = address }
            filter.doFilter(request, response, MockFilterChain())
        }
        repeat(3) { assertEquals(200, login("192.0.2.1").status) }
        val refused = login("192.0.2.1")
        assertEquals(429, refused.status)
        assertTrue(refused.getHeader("Retry-After")!!.toLong() > 0)
        assertEquals("application/problem+json", refused.contentType)
        assertEquals(200, login("192.0.2.2").status, "another address is not affected")
    }

    @Test
    fun `api requests are limited per token, not per address`() {
        val filter = RateLimitFilter(RateLimitProperties(requestsPerMinute = 2, loginPerMinute = 3))
        fun call(token: String) = MockHttpServletResponse().also { response ->
            val request = MockHttpServletRequest("GET", "/api/v1/orders").apply {
                remoteAddr = "192.0.2.1"
                addHeader("Authorization", "Bearer $token")
            }
            filter.doFilter(request, response, MockFilterChain())
        }
        repeat(2) { assertEquals(200, call("spri_aaaa_secret1").status) }
        assertEquals(429, call("spri_aaaa_othersecret").status, "same token prefix, same bucket")
        assertEquals(200, call("spri_bbbb_secret").status)
    }
}
