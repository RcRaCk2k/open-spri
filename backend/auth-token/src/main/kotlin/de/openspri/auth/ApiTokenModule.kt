package de.openspri.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock

/** Authentication module "api-token": `Authorization: Bearer spri_…`. */
class ApiTokenModule(private val tokens: ApiTokens, private val store: ApiTokenStore, private val clock: Clock = Clock.systemUTC()) : AuthenticationModule {
    override val id = "api-token"
    override val displayName = "API-Token (Long-Life)"
    override val interactiveLogin = false

    override fun configure(http: HttpSecurity) {
        http.addFilterBefore(Filter(), UsernamePasswordAuthenticationFilter::class.java)
    }

    private inner class Filter : OncePerRequestFilter() {
        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
            val plainText = tokenOf(request.getHeader("Authorization"))
            if (plainText != null) {
                val now = clock.instant()
                val token = tokens.verify(plainText, now)
                if (token == null) {
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    response.contentType = "application/problem+json"
                    response.writer.write("""{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Ungültiges, abgelaufenes oder widerrufenes API-Token"}""")
                    return
                }
                store.touch(token.id!!, now, request.remoteAddr)
                val actor = Actor(Actor.Kind.TOKEN, token.id, "${token.name} (${token.prefix})", token.scopes, token.partnerId)
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken.authenticated(actor, null, token.scopes.map { it.authority })
            }
            chain.doFilter(request, response)
        }

        /**
         * `Bearer spri_…`, or Basic-Auth with the token as password (AUFGABE 5.4: Replikat download "per Token oder
         * Basic-Auth"); the user name is ignored.
         */
        private fun tokenOf(header: String?): String? = when {
            header == null -> null
            header.startsWith("Bearer ${ApiTokens.PREFIX}") -> header.removePrefix("Bearer ").trim()
            header.startsWith("Basic ") -> runCatching { String(java.util.Base64.getDecoder().decode(header.removePrefix("Basic ").trim())) }
                .getOrNull()?.substringAfter(':', "")?.takeIf { it.startsWith(ApiTokens.PREFIX) }
            else -> null
        }
    }
}
