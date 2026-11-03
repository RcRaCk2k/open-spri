package de.openspri.rest.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/**
 * Token bucket per key: [capacity] requests at once, refilled continuously at `capacity` per minute.
 * Pure logic with an injectable clock (nanoseconds), see RateLimiterTest.
 */
class RateLimiter(private val capacity: Int, private val nanoTime: () -> Long = System::nanoTime) {

    private class Bucket(var tokens: Double, var updated: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val refillPerNano = capacity / 60e9

    /** Takes one request from the bucket of [key]. Returns 0 if allowed, otherwise the seconds until the next one is. */
    fun acquire(key: String): Long {
        val now = nanoTime()
        if (buckets.size > MAX_KEYS) evictFull(now)
        val bucket = buckets.computeIfAbsent(key) { Bucket(capacity.toDouble(), now) }
        synchronized(bucket) {
            bucket.tokens = min(capacity.toDouble(), bucket.tokens + (now - bucket.updated) * refillPerNano)
            bucket.updated = now
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                return 0
            }
            return ceil((1.0 - bucket.tokens) / refillPerNano / 1e9).toLong().coerceAtLeast(1)
        }
    }

    /** Buckets that are full again carry no state and can go. */
    private fun evictFull(now: Long) {
        buckets.entries.removeIf { (_, b) -> synchronized(b) { b.tokens + (now - b.updated) * refillPerNano >= capacity } }
    }

    private companion object {
        const val MAX_KEYS = 10_000
    }
}

/**
 * Rate limiting of the REST API (AUFGABE 7.3: "Rate Limiting je Token"). Runs before Spring Security, so that
 * a flood of requests costs no password hashing or database access:
 *
 * - Login and setup: [RateLimitProperties.loginPerMinute] per client address (protects against password guessing).
 * - Everything else under /api and /replikat: [RateLimitProperties.requestsPerMinute] per API token (its public
 *   prefix), otherwise per session, otherwise per client address.
 *
 * The S/PRI endpoint (/spri) is not limited here: a partner must always get a TEQ (11.2.3.1). Exceeding the limit
 * answers 429 with `Retry-After` (RFC 9457 body).
 */
class RateLimitFilter(properties: RateLimitProperties) : OncePerRequestFilter() {

    private val requests = RateLimiter(properties.requestsPerMinute)
    private val logins = RateLimiter(properties.loginPerMinute)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI.removePrefix(request.contextPath)
        val wait = if (request.method == "POST" && LOGIN_PATHS.any { path == it || path.startsWith("$it/") }) {
            logins.acquire("ip:" + request.remoteAddr)
        } else {
            requests.acquire(key(request))
        }
        if (wait == 0L) return chain.doFilter(request, response)
        response.status = 429
        response.setHeader("Retry-After", wait.toString())
        response.contentType = "application/problem+json"
        response.outputStream.write(
            """{"type":"about:blank","status":429,"title":"Too Many Requests","detail":"Zu viele Anfragen. Bitte nach $wait s erneut versuchen."}"""
                .toByteArray(),
        )
    }

    private fun key(request: HttpServletRequest): String {
        val bearer = request.getHeader("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
        if (bearer != null) {
            // spri_<prefix>_<secret>: the prefix identifies the token and is not secret; anything else is hashed.
            val parts = bearer.split('_')
            return "token:" + if (parts.size == 3 && parts[0] == "spri") parts[1] else sha256(bearer)
        }
        val session = request.cookies?.firstOrNull { it.name == SESSION_COOKIE }?.value
        if (session != null) return "session:" + sha256(session)
        return "ip:" + request.remoteAddr
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SESSION_COOKIE = "JSESSIONID"
        val LOGIN_PATHS = listOf("/api/v1/auth/login", "/api/v1/setup")
    }
}

/** `openspri.rate-limit.*` (environment OPENSPRI_RATE_LIMIT_…). */
@ConfigurationProperties("openspri.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,
    /** Requests per minute and token, session or client address; also the burst size. */
    val requestsPerMinute: Int = 1200,
    /** Login and setup attempts per minute and client address. */
    val loginPerMinute: Int = 20,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties::class)
class RateLimitConfiguration {
    /** Before the Spring Security filter chain (order -100). */
    @Bean
    fun rateLimitFilter(properties: RateLimitProperties) = FilterRegistrationBean(RateLimitFilter(properties)).apply {
        order = -200
        isEnabled = properties.enabled
        addUrlPatterns("/api/*", "/replikat/*", "/metrics")
    }
}
