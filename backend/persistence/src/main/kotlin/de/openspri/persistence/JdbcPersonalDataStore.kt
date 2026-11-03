package de.openspri.persistence

import de.openspri.domain.privacy.PersonalDataStore
import de.openspri.domain.privacy.PurgeResult
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

/**
 * Retention of end customer data (NF-10, ADR-0010) on the database. "Still needed" means: an order with the same
 * partner and external order number that is open or was finished at or after the cutoff
 * (`spri_order.updated_at` is the time of the last change, for finished orders the completion).
 */
@Repository
class JdbcPersonalDataStore(private val jdbc: JdbcClient) : PersonalDataStore {

    @Transactional
    override fun purgeBefore(cutoff: Instant, now: Instant): PurgeResult {
        val params = mapOf("cutoff" to Timestamp.from(cutoff), "now" to Timestamp.from(now))
        fun stillNeeded(alias: String) =
            """EXISTS (SELECT 1 FROM spri_order o WHERE o.partner_id = $alias.partner_id
                   AND o.external_order_number = $alias.external_order_number
                   AND (o.finished = FALSE OR o.updated_at >= :cutoff))"""

        // The TEQ response carries no end customer data and stays; the request is replaced by an empty body.
        val archive = jdbc.sql(
            """UPDATE soap_archive a SET a.request = '', a.content_purged_at = :now
               WHERE a.started_at < :cutoff AND a.content_purged_at IS NULL AND NOT ${stillNeeded("a")}""",
        ).params(params).update()
        val inbox = jdbc.sql(
            """UPDATE inbox_message i SET i.payload = '', i.content_purged_at = :now
               WHERE i.received_at < :cutoff AND i.status = 'PROCESSED' AND i.content_purged_at IS NULL AND NOT ${stillNeeded("i")}""",
        ).params(params).update()
        val outbox = jdbc.sql(
            """UPDATE outbox_message m SET m.payload = '', m.content_purged_at = :now
               WHERE m.created_at < :cutoff AND m.status IN ('DELIVERED', 'DELIVERED_WITH_ERROR', 'REJECTED', 'SENT_BY_EMAIL', 'CANCELLED')
                 AND m.content_purged_at IS NULL AND NOT ${stillNeeded("m")}""",
        ).params(params).update()
        // Drafts never sent: the order row (phase DRAFT) only reserves the number and stays.
        val drafts = jdbc.sql("DELETE FROM spri_order_draft WHERE updated_at < :cutoff").params(params).update()
        // The R-10 key may be a normalized address; it is only checked against open orders.
        val orders = jdbc.sql(
            "UPDATE spri_order SET infrastructure_key = NULL WHERE finished = TRUE AND updated_at < :cutoff AND infrastructure_key IS NOT NULL",
        ).params(params).update()
        return PurgeResult(archive, inbox, outbox, drafts, orders)
    }
}
