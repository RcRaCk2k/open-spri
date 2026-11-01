package de.openspri.persistence

import de.openspri.domain.exchange.InboundMessage
import de.openspri.domain.exchange.InboxQueue
import de.openspri.domain.exchange.PendingInboundMessage
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.partner.PartnerId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant

/**
 * Inbound queue on table inbox_message. Each item is processed in its own transaction together with the
 * status update. An item whose processing fails is marked FAILED with the error and does not block the
 * following items; it can be reprocessed after the cause has been fixed (M3: retry, monitoring).
 */
@Repository
class JdbcInboxQueue(private val jdbc: JdbcClient, private val transactions: TransactionTemplate) : InboxQueue {

    override fun processNext(handler: (PendingInboundMessage) -> List<String>): Boolean {
        val item = jdbc.sql(
            "SELECT id, archive_id, partner_id, operation, external_order_number, payload FROM inbox_message WHERE status = 'RECEIVED' ORDER BY id LIMIT 1",
        ).query { rs, _ ->
            PendingInboundMessage(
                id = rs.getLong("id"),
                archiveId = rs.getLong("archive_id"),
                message = InboundMessage(
                    partnerId = PartnerId(rs.getLong("partner_id")),
                    operation = SoapOperation.fromWsdlName(rs.getString("operation"))!!,
                    externalOrderNumber = rs.getString("external_order_number"),
                    payload = rs.getBytes("payload"),
                ),
            )
        }.optional().orElse(null) ?: return false

        try {
            transactions.executeWithoutResult {
                val findings = handler(item)
                mark(item.id, "PROCESSED", findings.joinToString("\n").ifEmpty { null }, null)
            }
        } catch (e: Exception) {
            mark(item.id, "FAILED", null, "${e.javaClass.simpleName}: ${e.message}".take(2000))
        }
        return true
    }

    private fun mark(id: Long, status: String, findings: String?, error: String?) {
        jdbc.sql("UPDATE inbox_message SET status = :s, processed_at = :t, findings = :f, error = :e WHERE id = :id")
            .param("s", status).param("t", Timestamp.from(Instant.now())).param("f", findings?.take(4000)).param("e", error).param("id", id)
            .update()
    }
}
