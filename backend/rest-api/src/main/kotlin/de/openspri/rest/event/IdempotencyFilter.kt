package de.openspri.rest.event

import de.openspri.auth.Actor
import de.openspri.domain.event.IdempotencyClaim
import de.openspri.domain.event.IdempotencyStore
import de.openspri.domain.event.StoredResponse
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.time.Instant

/**
 * `Idempotency-Key` for all POSTs that trigger an S/PRI message (AUFGABE 8.5): the first request with a key is
 * executed; a repetition with the same key and the same request gets the stored answer again
 * (`Idempotent-Replayed: true`) without sending anything. The key is per user/token and kept for
 * `openspri.idempotency.retention` (24 h). Only successful answers are stored; after an error the key may be
 * used again. Runs after Spring Security, so the actor is known.
 */
class IdempotencyFilter(private val store: IdempotencyStore) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "POST" || request.getHeader(HEADER) == null || !SPRI_PATHS.matches(request.requestURI)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val key = request.getHeader(HEADER).trim()
        if (key.isEmpty() || key.length > 200 || key.any { it.code !in 0x21..0x7e }) {
            return problem(response, 400, "Idempotency-Key: 1 bis 200 druckbare ASCII-Zeichen ohne Leerzeichen")
        }
        val actor = SecurityContextHolder.getContext().authentication?.principal as? Actor ?: return chain.doFilter(request, response)
        val body = request.inputStream.readAllBytes()
        val hash = sha256(request.method.toByteArray() + request.requestURI.toByteArray() + (request.queryString ?: "").toByteArray() + body)

        when (val claim = store.claim(actor.label, key, hash, Instant.now())) {
            is IdempotencyClaim.Replay -> {
                response.status = claim.response.status
                claim.response.contentType?.let { response.contentType = it }
                response.setHeader("Idempotent-Replayed", "true")
                response.outputStream.write(claim.response.body)
            }
            IdempotencyClaim.InProgress -> problem(response, 409, "Eine Anfrage mit diesem Idempotency-Key läuft noch")
            IdempotencyClaim.Mismatch -> problem(response, 422, "Der Idempotency-Key wurde bereits für eine andere Anfrage verwendet")
            is IdempotencyClaim.Claimed -> {
                val wrapped = ContentCachingResponseWrapper(response)
                try {
                    chain.doFilter(CachedBodyRequest(request, body), wrapped)
                } catch (e: Exception) {
                    store.release(claim.id)
                    throw e
                }
                if (wrapped.status in 200..299) {
                    store.complete(claim.id, StoredResponse(wrapped.status, wrapped.contentAsByteArray, wrapped.contentType))
                } else {
                    store.release(claim.id)
                }
                wrapped.copyBodyToResponse()
            }
        }
    }

    private fun problem(response: HttpServletResponse, status: Int, detail: String) {
        response.status = status
        response.contentType = "application/problem+json"
        val escaped = detail.replace("\\", "\\\\").replace("\"", "\\\"")
        response.outputStream.write("""{"type":"about:blank","status":$status,"title":"Idempotency-Key","detail":"$escaped"}""".toByteArray())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class CachedBodyRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream {
            val input = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read() = input.read()
                override fun read(b: ByteArray, off: Int, len: Int) = input.read(b, off, len)
                override fun isFinished() = input.available() == 0
                override fun isReady() = true
                override fun setReadListener(listener: ReadListener) = throw UnsupportedOperationException()
            }
        }

        override fun getReader() = BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))
    }

    companion object {
        const val HEADER = "Idempotency-Key"
        /** Orders, requests and messages; never login, tokens or setup (their answers contain secrets). */
        val SPRI_PATHS = Regex("^/api/v1/orders(/\\d+/(cancel|reschedule|complaint|messages))?/?$")
    }
}

@Configuration(proxyBeanMethods = false)
class IdempotencyConfiguration {
    /** Order 0: after the Spring Security filter chain (-100). */
    @Bean
    fun idempotencyFilter(store: IdempotencyStore) = FilterRegistrationBean(IdempotencyFilter(store)).apply {
        order = 0
        addUrlPatterns("/api/v1/orders", "/api/v1/orders/*")
    }
}
