package de.openspri.persistence

import de.openspri.domain.delivery.OutageRegistry
import de.openspri.domain.delivery.PartnerOutage
import de.openspri.domain.order.Transactions
import de.openspri.domain.partner.PartnerId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/** Partner outages (specification 11.2.3.2): one notification per day and outage. */
@Repository
class JdbcOutageRegistry(private val jdbc: JdbcClient) : OutageRegistry {

    override fun failed(partnerId: PartnerId, now: Instant, notifyEvery: Duration): PartnerOutage? {
        jdbc.sql("INSERT IGNORE INTO partner_outage (partner_id, since) VALUES (:p, :now)")
            .param("p", partnerId.value).param("now", Timestamp.from(now)).update()
        // atomic "claim" of the notification: only one instance (and one attempt per interval) wins
        val claimed = jdbc.sql(
            """UPDATE partner_outage SET last_notified_at = :now
               WHERE partner_id = :p AND (last_notified_at IS NULL OR last_notified_at <= :due)""",
        ).param("now", Timestamp.from(now)).param("p", partnerId.value).param("due", Timestamp.from(now.minus(notifyEvery))).update()
        if (claimed == 0) return null
        return jdbc.sql("SELECT since, last_notified_at FROM partner_outage WHERE partner_id = :p").param("p", partnerId.value)
            .query { rs, _ -> PartnerOutage(partnerId, rs.getTimestamp("since").toInstant(), rs.getTimestamp("last_notified_at")?.toInstant()) }
            .single()
    }

    override fun succeeded(partnerId: PartnerId) {
        jdbc.sql("DELETE FROM partner_outage WHERE partner_id = :p").param("p", partnerId.value).update()
    }
}

/** [Transactions] port for the domain services, backed by Spring's TransactionTemplate. */
@Repository
class SpringTransactions(private val template: TransactionTemplate) : Transactions {
    override fun <T> run(block: () -> T): T = template.execute { block() }
}
