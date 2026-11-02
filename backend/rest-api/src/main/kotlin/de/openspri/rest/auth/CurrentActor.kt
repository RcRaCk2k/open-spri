package de.openspri.rest.auth

import de.openspri.auth.Actor
import org.springframework.security.core.context.SecurityContextHolder

/** The authenticated actor of the current request. */
fun currentActor(): Actor =
    SecurityContextHolder.getContext().authentication?.principal as? Actor
        ?: throw org.springframework.security.access.AccessDeniedException("not authenticated")

/** Expressions for @PreAuthorize. */
object Requires {
    const val ORDERS_READ = "hasAuthority('SCOPE_orders:read')"
    const val ORDERS_WRITE = "hasAuthority('SCOPE_orders:write')"
    const val LE_MESSAGES_WRITE = "hasAuthority('SCOPE_le:messages:write')"
    const val DELIVERIES_READ = "hasAuthority('SCOPE_deliveries:read')"
    const val DELIVERIES_WRITE = "hasAuthority('SCOPE_deliveries:write')"
    const val PARTNERS_READ = "hasAuthority('SCOPE_partners:read')"
    const val PARTNERS_ADMIN = "hasAuthority('SCOPE_partners:admin')"
    const val EVENTS_READ = "hasAuthority('SCOPE_events:read')"
    const val WEBHOOKS_ADMIN = "hasAuthority('SCOPE_webhooks:admin')"
    const val MESSAGE_CODES_ADMIN = "hasAuthority('SCOPE_message-codes:admin')"
    const val USERS_ADMIN = "hasAuthority('SCOPE_users:admin')"
    const val SETTINGS_ADMIN = "hasAuthority('SCOPE_settings:admin')"
    const val AUDIT_READ = "hasAuthority('SCOPE_audit:read')"
    const val ORDERS_OVERRIDE = "hasAuthority('SCOPE_orders:override')"
}
