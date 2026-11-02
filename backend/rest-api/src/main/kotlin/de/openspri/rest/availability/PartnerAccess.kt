package de.openspri.rest.availability

import de.openspri.auth.Actor
import de.openspri.auth.Scope
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerAdministration
import de.openspri.domain.partner.PartnerId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.net.InetAddress

/**
 * Access of a partner to our Replikat and online query (AUFGABE 5.4, 7.5): an API token of role PARTNER (Bearer or
 * Basic-Auth with the token as password), bound to an enabled partner, and a source address from the partner's IP
 * allowlist (as for the S/PRI endpoint). The address is the one Tomcat determines, behind nginx from X-Forwarded-For.
 */
@Component
class PartnerAccess(private val partners: PartnerAdministration) {

    fun require(request: HttpServletRequest): Partner {
        val actor = SecurityContextHolder.getContext().authentication?.principal as? Actor
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Partner-Token fehlt (Bearer oder Basic-Auth)")
        if (Scope.PARTNER_AVAILABILITY !in actor.scopes || actor.partnerId == null) throw AccessDeniedException("Nur mit einem Partner-Token (Rolle PARTNER)")
        val partner = partners.get(PartnerId(actor.partnerId!!))?.takeIf { it.enabled } ?: throw AccessDeniedException("Partner ist deaktiviert")
        val address = runCatching { InetAddress.getByName(request.remoteAddr) }.getOrNull()
        if (address == null || partner.allowedSourceNetworks.none { address in it }) {
            throw AccessDeniedException("Quelladresse ${request.remoteAddr} ist für ${partner.name} nicht freigeschaltet")
        }
        return partner
    }
}
