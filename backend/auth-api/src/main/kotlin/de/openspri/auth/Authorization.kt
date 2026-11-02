package de.openspri.auth

import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.time.Instant

/** Fine-grained permissions (AUFGABE 7.5). Spring authority: `SCOPE_<value>`. */
enum class Scope(val value: String) {
    ORDERS_READ("orders:read"),
    ORDERS_WRITE("orders:write"),
    LE_MESSAGES_WRITE("le:messages:write"),
    DELIVERIES_READ("deliveries:read"),
    DELIVERIES_WRITE("deliveries:write"),
    PARTNERS_READ("partners:read"),
    PARTNERS_ADMIN("partners:admin"),
    AVAILABILITY_READ("availability:read"),
    /** Own availabilities (Leistungserbringer), Replikat releases and sources (AUFGABE 5.4). */
    AVAILABILITY_ADMIN("availability:admin"),
    /**
     * Partner access to our Replikat and online query (AUFGABE 7.5 `partner:<id>:availability`): only in tokens of
     * role PARTNER, which are bound to one partner ([Actor.partnerId]).
     */
    PARTNER_AVAILABILITY("partner:availability"),
    EVENTS_READ("events:read"),
    WEBHOOKS_ADMIN("webhooks:admin"),
    MESSAGE_CODES_ADMIN("message-codes:admin"),
    USERS_ADMIN("users:admin"),
    SETTINGS_ADMIN("settings:admin"),
    AUDIT_READ("audit:read"),
    /**
     * Send own orders that our order rules would reject (KFT negative test cases, ADR-0008). In no role: only an
     * administrator can put it explicitly into an API token ([Role.grantable]).
     */
    ORDERS_OVERRIDE("orders:override"),
    ;

    val authority: GrantedAuthority get() = SimpleGrantedAuthority("SCOPE_$value")

    companion object {
        fun fromValue(value: String) = entries.firstOrNull { it.value == value } ?: throw IllegalArgumentException("unknown scope '$value'")
    }
}

/** Roles bundle scopes. A token may be restricted to a subset of its role's scopes. */
enum class Role(val scopes: Set<Scope>) {
    ADMIN(Scope.entries.toSet() - Scope.ORDERS_OVERRIDE - Scope.PARTNER_AVAILABILITY),
    OPERATOR_AG(setOf(Scope.ORDERS_READ, Scope.ORDERS_WRITE, Scope.DELIVERIES_READ, Scope.DELIVERIES_WRITE, Scope.PARTNERS_READ, Scope.AVAILABILITY_READ, Scope.EVENTS_READ)),
    OPERATOR_LE(setOf(Scope.ORDERS_READ, Scope.LE_MESSAGES_WRITE, Scope.DELIVERIES_READ, Scope.DELIVERIES_WRITE, Scope.PARTNERS_READ, Scope.AVAILABILITY_READ, Scope.AVAILABILITY_ADMIN, Scope.EVENTS_READ)),
    VIEWER(setOf(Scope.ORDERS_READ, Scope.DELIVERIES_READ, Scope.PARTNERS_READ, Scope.AVAILABILITY_READ, Scope.EVENTS_READ, Scope.AUDIT_READ)),
    /** External systems (CRM, workflow): order and message traffic, no administration. */
    INTEGRATION(setOf(Scope.ORDERS_READ, Scope.ORDERS_WRITE, Scope.LE_MESSAGES_WRITE, Scope.DELIVERIES_READ, Scope.PARTNERS_READ, Scope.AVAILABILITY_READ, Scope.EVENTS_READ)),
    /** A partner downloading our Replikat or using our online query; the token is bound to the partner. */
    PARTNER(setOf(Scope.PARTNER_AVAILABILITY)),
    ;

    /** Scopes a token of this role may carry: its own, for ADMIN also `orders:override` on explicit request (ADR-0008). */
    val grantable: Set<Scope> get() = if (this == ADMIN) scopes + Scope.ORDERS_OVERRIDE else scopes
}

/** Who is acting. Stored as Spring Security principal; written to the audit log. */
data class Actor(val kind: Kind, val id: Long?, val name: String, val scopes: Set<Scope>, val partnerId: Long? = null) {
    enum class Kind { USER, TOKEN, SETUP, SYSTEM }

    val label: String get() = "${kind.name.lowercase()}:$name"
}

/** A pluggable authentication method (AUFGABE 7.5). Modules are discovered as Spring beans. */
interface AuthenticationModule {
    /** Stable id, e.g. "api-token", "local-users", later "oidc". */
    val id: String

    /** Shown in the login page and in GET /api/v1/auth/modules. */
    val displayName: String

    /** true if the module offers an interactive login for the web frontend. */
    val interactiveLogin: Boolean

    /** Adds filters/providers to the security filter chain of the REST API. */
    fun configure(http: HttpSecurity)
}

/** Audit log (NF-10): who changed what. */
interface AuditLog {
    fun record(actor: Actor, action: String, target: String, details: String? = null)
}

data class AuditEntry(val id: Long, val at: Instant, val actor: String, val action: String, val target: String, val details: String?)

/**
 * Search in the audit log, newest first. `actor` and `target` match as substring, `action` as prefix (e.g. `order.`);
 * `cursor` is the id of the last entry of the previous page.
 */
data class AuditFilter(
    val actor: String? = null,
    val action: String? = null,
    val target: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val cursor: Long? = null,
    val limit: Int = 50,
)

/** Reading the audit log (scope `audit:read`). */
interface AuditTrail {
    fun search(filter: AuditFilter): List<AuditEntry>
}
