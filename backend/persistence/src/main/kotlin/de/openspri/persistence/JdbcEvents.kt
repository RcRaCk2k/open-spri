package de.openspri.persistence

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.delivery.DeliveryStatus
import de.openspri.domain.event.BusinessEvent
import de.openspri.domain.event.EventFeed
import de.openspri.domain.event.EventLog
import de.openspri.domain.event.EventType
import de.openspri.domain.event.WebhookStore
import de.openspri.domain.event.WebhookSubscription
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.OrderId
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.Phase
import de.openspri.domain.partner.PartnerId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Event feed on table event. [publish] joins the caller's transaction (inbox item, outbox dispatch or REST
 * action), so an event is visible exactly if the change was committed.
 *
 * Ids come from AUTO_INCREMENT. Two transactions may commit in a different order than they got their ids, so a
 * reader that already saw id 11 could miss id 10 committed a moment later. The event is the last statement
 * before the commit, so the gap is short: the feed and the webhooks only show events whose insert is at least
 * [VISIBILITY_DELAY_MS] old (database time).
 */
@Repository
class JdbcEvents(private val jdbc: JdbcClient, private val transactions: TransactionTemplate) : EventLog, EventFeed, WebhookStore {

    override fun publish(event: BusinessEvent) {
        jdbc.sql(
            """INSERT INTO event (tenant_id, type, partner_id, order_id, role, external_order_number, business_case, phase, message_type,
                   change_indicator, codes, findings, delivery_id, delivery_status, created_at)
               VALUES (:t, :type, :partner, :order, :role, :number, :bc, :phase, :mt, :ci, :codes, :findings, :delivery, :status, :at)""",
        )
            .param("t", Tenant.DEFAULT).param("type", event.type.wireName).param("partner", event.partnerId.value)
            .param("order", event.orderId?.value).param("role", event.role?.name).param("number", event.externalOrderNumber)
            .param("bc", event.businessCase?.code).param("phase", event.phase?.name).param("mt", event.messageType?.code)
            .param("ci", event.changeIndicator?.name).param("codes", event.codes.joinToString(",").ifEmpty { null })
            .param("findings", event.findings.joinToString("\n").take(4000).ifEmpty { null })
            .param("delivery", event.deliveryId).param("status", event.deliveryStatus?.name).param("at", Timestamp.from(event.at))
            .update()
    }

    override fun after(id: Long, limit: Int, types: Set<EventType>?): List<BusinessEvent> {
        val typeFilter = if (types.isNullOrEmpty()) "" else " AND type IN (:types)"
        var query = jdbc.sql("SELECT * FROM event WHERE tenant_id = :t AND id > :id AND $VISIBLE$typeFilter ORDER BY id LIMIT :limit")
            .param("t", Tenant.DEFAULT).param("id", id).param("limit", limit.coerceIn(1, 1000))
        if (!types.isNullOrEmpty()) query = query.param("types", types.map { it.wireName })
        return query.query { rs, _ -> map(rs) }.list()
    }

    override fun latestId(): Long =
        jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM event WHERE tenant_id = :t AND $VISIBLE").param("t", Tenant.DEFAULT).query(Long::class.java).single()

    override fun purgeBefore(time: Instant): Int =
        jdbc.sql("DELETE FROM event WHERE created_at < :t").param("t", Timestamp.from(time)).update()

    private fun map(rs: ResultSet) = BusinessEvent(
        id = rs.getLong("id"),
        type = EventType.fromWireName(rs.getString("type")),
        partnerId = PartnerId(rs.getLong("partner_id")),
        orderId = rs.getLong("order_id").takeIf { !rs.wasNull() }?.let(::OrderId),
        role = rs.getString("role")?.let(OrderRole::valueOf),
        externalOrderNumber = rs.getString("external_order_number"),
        businessCase = rs.getString("business_case")?.let(BusinessCase::fromCode),
        phase = rs.getString("phase")?.let(Phase::valueOf),
        messageType = rs.getString("message_type")?.let(MessageType::fromCode),
        changeIndicator = rs.getString("change_indicator")?.let(ChangeIndicator::valueOf),
        codes = rs.getString("codes")?.split(',').orEmpty(),
        findings = rs.getString("findings")?.split('\n').orEmpty(),
        deliveryId = rs.getLong("delivery_id").takeIf { !rs.wasNull() },
        deliveryStatus = rs.getString("delivery_status")?.let(DeliveryStatus::valueOf),
        at = rs.getTimestamp("created_at").toInstant(),
    )

    // --- webhooks ---------------------------------------------------------------------------------

    override fun list(): List<WebhookSubscription> =
        jdbc.sql("SELECT * FROM webhook_subscription WHERE tenant_id = :t ORDER BY id").param("t", Tenant.DEFAULT).query { rs, _ -> webhook(rs) }.list()

    override fun get(id: Long): WebhookSubscription? =
        jdbc.sql("SELECT * FROM webhook_subscription WHERE id = :id AND tenant_id = :t").param("id", id).param("t", Tenant.DEFAULT)
            .query { rs, _ -> webhook(rs) }.optional().orElse(null)

    override fun create(subscription: WebhookSubscription): WebhookSubscription {
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """INSERT INTO webhook_subscription (tenant_id, url, secret_encrypted, types, enabled, description, last_event_id, next_attempt_at, created_at)
               VALUES (:t, :url, :secret, :types, :enabled, :description, :last, :next, :created)""",
        )
            .param("t", Tenant.DEFAULT).param("url", subscription.url).param("secret", subscription.encryptedSecret)
            .param("types", types(subscription.types)).param("enabled", subscription.enabled).param("description", subscription.description)
            .param("last", subscription.lastEventId).param("next", Timestamp.from(subscription.nextAttemptAt))
            .param("created", Timestamp.from(subscription.createdAt))
            .update(keys, "id")
        return subscription.copy(id = keys.key!!.toLong())
    }

    override fun update(id: Long, url: String, types: Set<EventType>, enabled: Boolean, description: String?): Boolean =
        jdbc.sql(
            // MariaDB evaluates SET from left to right: re-enabling (old value false) resets the backoff first
            """UPDATE webhook_subscription SET failures = IF(:enabled AND NOT enabled, 0, failures),
                   next_attempt_at = IF(:enabled AND NOT enabled, NOW(3), next_attempt_at),
                   url = :url, types = :types, enabled = :enabled, description = :description
               WHERE id = :id AND tenant_id = :t""",
        ).param("url", url).param("types", types(types)).param("enabled", enabled).param("description", description)
            .param("id", id).param("t", Tenant.DEFAULT).update() == 1

    override fun delete(id: Long): Boolean =
        jdbc.sql("DELETE FROM webhook_subscription WHERE id = :id AND tenant_id = :t").param("id", id).param("t", Tenant.DEFAULT).update() == 1

    override fun deliverNext(now: Instant, deliver: (WebhookSubscription) -> WebhookSubscription): Boolean =
        transactions.execute {
            val due = jdbc.sql(
                """SELECT * FROM webhook_subscription w
                   WHERE w.enabled AND w.next_attempt_at <= :now
                     AND EXISTS (SELECT 1 FROM event WHERE event.tenant_id = w.tenant_id AND event.id > w.last_event_id AND $VISIBLE)
                   ORDER BY w.next_attempt_at, w.id LIMIT 1
                   FOR UPDATE SKIP LOCKED""",
            ).param("now", Timestamp.from(now)).query { rs, _ -> webhook(rs) }.optional().orElse(null) ?: return@execute false
            val progress = deliver(due)
            jdbc.sql(
                """UPDATE webhook_subscription SET last_event_id = :last, failures = :failures, next_attempt_at = :next,
                       last_error = :error, last_success_at = :success WHERE id = :id""",
            )
                .param("last", progress.lastEventId).param("failures", progress.failures).param("next", Timestamp.from(progress.nextAttemptAt))
                .param("error", progress.lastError?.take(2000)).param("success", progress.lastSuccessAt?.let(Timestamp::from)).param("id", due.id)
                .update()
            true
        } == true

    companion object {
        const val VISIBILITY_DELAY_MS = 2000
        private const val VISIBLE = "inserted_at <= NOW(3) - INTERVAL ${VISIBILITY_DELAY_MS * 1000} MICROSECOND"
    }

    private fun types(types: Set<EventType>) = types.joinToString(",") { it.wireName }.ifEmpty { null }

    private fun webhook(rs: ResultSet) = WebhookSubscription(
        id = rs.getLong("id"),
        url = rs.getString("url"),
        encryptedSecret = rs.getBytes("secret_encrypted"),
        types = rs.getString("types")?.split(',')?.map(EventType::fromWireName)?.toSet().orEmpty(),
        enabled = rs.getBoolean("enabled"),
        description = rs.getString("description"),
        lastEventId = rs.getLong("last_event_id"),
        failures = rs.getInt("failures"),
        nextAttemptAt = rs.getTimestamp("next_attempt_at").toInstant(),
        lastError = rs.getString("last_error"),
        lastSuccessAt = rs.getTimestamp("last_success_at")?.toInstant(),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )
}
