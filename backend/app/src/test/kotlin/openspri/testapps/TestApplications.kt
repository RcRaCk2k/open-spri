package openspri.testapps

// Deliberately outside de.openspri: the component scan of OpenSpriApplication must not pick these up.

import de.openspri.soap.InMemoryInboundStore
import de.openspri.soap.InMemoryOutboundArchive
import de.openspri.soap.InMemoryPartnerDirectory
import de.openspri.soap.config.SpriSoapConfiguration
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import

private const val DATASOURCE = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
private const val FLYWAY = "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"

/** In-memory stores of the authentication modules. */
open class InMemoryAuthStores {
    @Bean open fun userStore() = InMemoryUserStore()
    @Bean open fun apiTokenStore() = InMemoryApiTokenStore()
    @Bean open fun auditLog() = InMemoryAuditLog()
}

class InMemoryUserStore : de.openspri.auth.UserStore {
    val users = mutableListOf<de.openspri.auth.User>()
    override fun save(user: de.openspri.auth.User): de.openspri.auth.User {
        val stored = if (user.id == null) user.copy(id = users.size + 1L) else user
        users.removeIf { it.id == stored.id }; users += stored; return stored
    }
    override fun findByUsername(username: String) = users.firstOrNull { it.username == username }
    override fun findById(id: Long) = users.firstOrNull { it.id == id }
    override fun list() = users.toList()
    override fun count() = users.size.toLong()
    override fun touchLogin(id: Long, at: java.time.Instant) = Unit
}

class InMemoryApiTokenStore : de.openspri.auth.ApiTokenStore {
    val tokens = mutableListOf<de.openspri.auth.ApiToken>()
    override fun save(token: de.openspri.auth.ApiToken) = token.copy(id = tokens.size + 1L).also { tokens += it }
    override fun findByPrefix(prefix: String) = tokens.firstOrNull { it.prefix == prefix }
    override fun list() = tokens.toList()
    override fun revoke(id: Long, at: java.time.Instant): Boolean {
        val i = tokens.indexOfFirst { it.id == id && it.revokedAt == null }
        if (i < 0) return false
        tokens[i] = tokens[i].copy(revokedAt = at); return true
    }
    override fun touch(id: Long, at: java.time.Instant, ip: String?) = Unit
}

class InMemoryAuditLog : de.openspri.auth.AuditLog {
    val entries = mutableListOf<String>()
    override fun record(actor: de.openspri.auth.Actor, action: String, target: String, details: String?) { entries += "${actor.label} $action $target" }
}

/**
 * The whole application with in-memory ports instead of the database (runs without Docker).
 * The master key is a fixed test value.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = [DATASOURCE, FLYWAY])
@Import(SpriSoapConfiguration::class)
@ComponentScan("de.openspri.rest")
class SoapInMemoryTestApplication : InMemoryAuthStores() {
    @Bean fun partnerDirectory() = InMemoryPartnerDirectory()
    /** Administration for the REST tests; the SOAP tests use [partnerDirectory]. */
    @Bean fun partnerAdministration(): de.openspri.domain.partner.PartnerAdministration = de.openspri.domain.InMemoryPartners()
    @Bean fun inboundStore() = InMemoryInboundStore()
    @Bean fun outboundArchive() = InMemoryOutboundArchive()
    @Bean fun orderRepository() = de.openspri.domain.InMemoryOrderRepository()
    @Bean fun orderDrafts() = de.openspri.domain.InMemoryOrderDrafts()
    @Bean fun agreements() = de.openspri.domain.InMemoryAgreements()
    @Bean fun messageCodeStore() = de.openspri.soap.InMemoryMessageCodeStore()
    @Bean fun outbox() = de.openspri.domain.InMemoryOutbox()
    @Bean fun outageRegistry() = de.openspri.domain.InMemoryOutageRegistry()
    @Bean fun transactions(): de.openspri.domain.order.Transactions = de.openspri.domain.DirectTransactions
    @Bean fun settingsRepository() = de.openspri.domain.InMemorySettings()
    @Bean fun signingKeyRepository() = de.openspri.domain.InMemorySigningKeys()
    @Bean fun events() = de.openspri.domain.InMemoryEvents()
    @Bean fun idempotencyStore() = de.openspri.domain.InMemoryIdempotencyStore()
}
