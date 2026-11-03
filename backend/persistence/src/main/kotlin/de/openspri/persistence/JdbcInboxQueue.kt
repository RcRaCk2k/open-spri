package de.openspri.persistence

import de.openspri.domain.exchange.InboundMessage
import de.openspri.domain.exchange.InboxQueue
import de.openspri.domain.exchange.PendingInboundMessage
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.partner.PartnerId
import de.openspri.domain.query.InboxItem
import de.openspri.domain.query.InboxQueries
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant

/**
 * Inbound queue on table inbox_message.
 *
 * Each item is processed in one transaction together with its status update. Multi-instance safety and
 * order: the oldest RECEIVED item whose sequence key (partner + external order number) has no older
 * RECEIVED item is locked with `FOR UPDATE SKIP LOCKED`. While it is being processed, other instances skip it
 * and - because it is still RECEIVED for them - also all younger items of the same order. Items of
 * different orders are processed in parallel.
 *
 * A failing item is marked FAILED with the error. It does not block the following items of other orders;
 * items of the same order wait (FAILED blocks the key) until an operator has reprocessed or discarded it.
 */
@Repository
class JdbcInboxQueue(private val jdbc: JdbcClient, private val transactions: TransactionTemplate) : InboxQueue, InboxQueries {

    override fun processNext(handler: (PendingInboundMessage) -> List<String>): Boolean {
        var item: PendingInboundMessage? = null
        try {
            return transactions.execute {
                val selected = jdbc.sql(
                    """SELECT id, archive_id, partner_id, operation, external_order_number, payload FROM inbox_message i
                       WHERE i.status = 'RECEIVED'
                         AND NOT EXISTS (SELECT 1 FROM inbox_message e
                                         WHERE e.sequence_key = i.sequence_key AND e.id < i.id AND e.status IN ('RECEIVED', 'FAILED'))
                       ORDER BY i.id LIMIT 1
                       FOR UPDATE SKIP LOCKED""",
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
                }.optional().orElse(null) ?: return@execute false
                item = selected
                val findings = handler(selected)
                mark(selected.id, "PROCESSED", findings.joinToString("\n").ifEmpty { null }, null)
                true
            }
        } catch (e: Exception) {
            val failed = item ?: throw e
            transactions.executeWithoutResult { mark(failed.id, "FAILED", null, "${e.javaClass.simpleName}: ${e.message}".take(2000)) }
            return true
        }
    }

    override fun list(status: String?, beforeId: Long?, limit: Int): List<InboxItem> {
        val where = mutableListOf("1 = 1")
        val params = mutableMapOf<String, Any?>("limit" to limit.coerceIn(1, 500))
        status?.let { where += "status = :s"; params["s"] = it }
        beforeId?.let { where += "id < :before"; params["before"] = it }
        return jdbc.sql(
            """SELECT id, archive_id, partner_id, operation, external_order_number, status, received_at, processed_at, findings, error
               FROM inbox_message WHERE ${where.joinToString(" AND ")} ORDER BY id DESC LIMIT :limit""",
        ).params(params).query { rs, _ ->
            InboxItem(
                id = rs.getLong("id"),
                archiveId = rs.getLong("archive_id"),
                partnerId = PartnerId(rs.getLong("partner_id")),
                operation = rs.getString("operation"),
                externalOrderNumber = rs.getString("external_order_number"),
                status = rs.getString("status"),
                receivedAt = rs.getTimestamp("received_at").toInstant(),
                processedAt = rs.getTimestamp("processed_at")?.toInstant(),
                findings = rs.getString("findings"),
                error = rs.getString("error"),
            )
        }.list()
    }

    /** Operator action: process a FAILED item again. */
    override fun reprocess(id: Long): Boolean =
        jdbc.sql("UPDATE inbox_message SET status = 'RECEIVED', error = NULL WHERE id = :id AND status = 'FAILED'").param("id", id).update() == 1

    override fun countByStatus(): Map<String, Long> =
        jdbc.sql("SELECT status, COUNT(*) AS n FROM inbox_message GROUP BY status")
            .query { rs, _ -> rs.getString("status") to rs.getLong("n") }.list().toMap()

    private fun mark(id: Long, status: String, findings: String?, error: String?) {
        jdbc.sql("UPDATE inbox_message SET status = :s, processed_at = :t, findings = :f, error = :e WHERE id = :id")
            .param("s", status).param("t", Timestamp.from(Instant.now())).param("f", findings?.take(4000)).param("e", error).param("id", id)
            .update()
    }
}
