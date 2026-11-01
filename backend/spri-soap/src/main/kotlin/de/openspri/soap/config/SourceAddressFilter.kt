package de.openspri.soap.config

import de.openspri.domain.partner.PartnerDirectory
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import java.net.InetAddress

/**
 * Lets only allowlisted partner addresses reach the S/PRI endpoint (AK security document V3.0, 2.6).
 * The client address is the one resolved by the servlet container; behind the proxy it comes from
 * X-Forwarded-For, which Tomcat only trusts from internal proxy addresses (server.forward-headers-strategy).
 */
class SourceAddressFilter(private val partners: PartnerDirectory) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val address = runCatching { InetAddress.getByName(request.remoteAddr) }.getOrNull()
        if (address == null || partners.findBySourceAddress(address).isEmpty()) {
            log.warn("S/PRI access from {} denied: not allowlisted", request.remoteAddr)
            response.sendError(HttpServletResponse.SC_FORBIDDEN)
            return
        }
        chain.doFilter(request, response)
    }
}
