package de.openspri.persistence

import de.openspri.domain.delivery.DeliveryStatus
import de.openspri.domain.delivery.Outbox
import de.openspri.domain.delivery.OutboxEntry
import de.openspri.domain.delivery.RequestType
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.order.OrderId
import de.openspri.domain.partner.PartnerId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Outbox on table outbox_message (AUFGABE chapter 8.3).
 *
 * Ordering and multi-instance safety: [dispatchNext] runs in one transaction. It selects the oldest due
 * entry whose sequence key has no older blocking entry, locks it with `FOR UPDATE SKIP LOCKED` (MariaDB
 * >= 10.6) and keeps the lock while sending. Another instance skips the locked row; because the row is
 * still PENDING for everybody else, the NOT EXISTS condition also hides all younger entries of the same
 * key. So per key exactly one request is in flight, across all instances.
 */
@Repository
class JdbcOutbox(private val jdbc: JdbcClient, private val transactions: TransactionTemplate) : Outbox {

    private val blocking = DeliveryStatus.entries.filter { it.blocksKey }.joinToString(",") { "'${it.name}'" }

    override fun enqueue(entry: OutboxEntry): OutboxEntry {
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """INSERT INTO outbox_message (tenant_id, partner_id, order_id, request_type, operation, external_order_number, sequence_key,
                   payload, status, attempts, next_attempt_at, created_at)
               VALUES (:t, :p, :o, :type, :op, :n, :k, :payload, :s, :a, :next, :created)""",
        )
            .param("t", Tenant.DEFAULT).param("p", entry.partnerId.value).param("o", entry.orderId?.value)
            .param("type", entry.type.name).param("op", entry.operation.wsdlName).param("n", entry.externalOrderNumber)
            .param("k", entry.sequenceKey).param("payload", entry.payload).param("s", entry.status.name).param("a", entry.attempts)
            .param("next", Timestamp.from(entry.nextAttemptAt)).param("created", Timestamp.from(entry.createdAt))
            .update(keys, "id")
        return entry.copy(id = keys.key!!.toLong())
    }

    override fun dispatchNext(now: Instant, send: (OutboxEntry) -> OutboxEntry): Boolean =
        transactions.execute {
            val entry = jdbc.sql(
                """SELECT * FROM outbox_message o
                   WHERE o.status = 'PENDING' AND o.next_attempt_at <= :now
                     AND NOT EXISTS (SELECT 1 FROM outbox_message e
                                     WHERE e.sequence_key = o.sequence_key AND e.id < o.id AND e.status IN ($blocking))
                   ORDER BY o.id LIMIT 1
                   FOR UPDATE SKIP LOCKED""",
            ).param("now", Timestamp.from(now)).query { rs, _ -> map(rs) }.optional().orElse(null)
                ?: return@execute false
            update(send(entry))
            true
        }

    override fun retry(id: Long, now: Instant) = change(id, "status IN ('UNDELIVERABLE','UNCLEAR')", "status = 'PENDING', attempts = 0, next_attempt_at = :now", now)

    override fun markSentByEmail(id: Long, now: Instant) = change(id, "status IN ('UNDELIVERABLE','UNCLEAR')", "status = 'SENT_BY_EMAIL', finished_at = :now", now)

    override fun cancel(id: Long, now: Instant) = change(id, "status IN ('PENDING','UNDELIVERABLE','UNCLEAR')", "status = 'CANCELLED', finished_at = :now", now)

    /** Entries an operator has to look at (delivery monitor). */
    fun attention(): List<OutboxEntry> =
        jdbc.sql("SELECT * FROM outbox_message WHERE status IN ('UNDELIVERABLE','UNCLEAR','DELIVERED_WITH_ERROR','REJECTED') ORDER BY id")
            .query { rs, _ -> map(rs) }.list()

    fun byStatus(status: DeliveryStatus): List<OutboxEntry> =
        jdbc.sql("SELECT * FROM outbox_message WHERE status = :s ORDER BY id").param("s", status.name).query { rs, _ -> map(rs) }.list()

    private fun change(id: Long, condition: String, assignment: String, now: Instant): Boolean =
        jdbc.sql("UPDATE outbox_message SET $assignment WHERE id = :id AND $condition")
            .param("id", id).param("now", Timestamp.from(now)).update() == 1

    private fun update(entry: OutboxEntry) {
        jdbc.sql(
            """UPDATE outbox_message SET status = :s, attempts = :a, next_attempt_at = :next, last_error = :err, teq_code = :teq,
                   finished_at = :fin WHERE id = :id""",
        )
            .param("s", entry.status.name).param("a", entry.attempts).param("next", Timestamp.from(entry.nextAttemptAt))
            .param("err", entry.lastError?.take(2000)).param("teq", entry.teqCode)
            .param("fin", entry.finishedAt?.let(Timestamp::from)).param("id", entry.id)
            .update()
    }

    private fun map(rs: ResultSet) = OutboxEntry(
        id = rs.getLong("id"),
        partnerId = PartnerId(rs.getLong("partner_id")),
        orderId = rs.getLong("order_id").takeIf { !rs.wasNull() }?.let(::OrderId),
        type = RequestType.valueOf(rs.getString("request_type")),
        operation = SoapOperation.fromWsdlName(rs.getString("operation"))!!,
        externalOrderNumber = rs.getString("external_order_number"),
        sequenceKey = rs.getString("sequence_key"),
        payload = rs.getBytes("payload"),
        status = DeliveryStatus.valueOf(rs.getString("status")),
        attempts = rs.getInt("attempts"),
        nextAttemptAt = rs.getTimestamp("next_attempt_at").toInstant(),
        lastError = rs.getString("last_error"),
        teqCode = rs.getString("teq_code"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
    )
}
