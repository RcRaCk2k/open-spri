package de.openspri.soap.config

import de.openspri.domain.partner.PartnerDirectory
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import de.openspri.soap.SpriMetrics
import java.net.InetAddress
import java.time.Duration

/**
 * Lets only allowlisted partner addresses reach the S/PRI endpoint (AK security document V3.0, 2.6).
 * The client address is the one resolved by the servlet container; behind the proxy it comes from
 * X-Forwarded-For, which Tomcat only trusts from internal proxy addresses (server.forward-headers-strategy).
 */
class SourceAddressFilter(private val partners: PartnerDirectory, private val metrics: SpriMetrics = SpriMetrics.NONE) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val address = runCatching { InetAddress.getByName(request.remoteAddr) }.getOrNull()
        if (address == null || partners.findBySourceAddress(address).isEmpty()) {
            log.warn("S/PRI access from {} denied: not allowlisted", request.remoteAddr)
            // set the status directly: sendError would forward to /error (and through the REST security chain)
            response.status = HttpServletResponse.SC_FORBIDDEN
            metrics.inbound(null, null, "forbidden", Duration.ZERO)
            return
        }
        chain.doFilter(request, response)
    }
}
