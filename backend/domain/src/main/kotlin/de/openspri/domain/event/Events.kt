package de.openspri.domain.event

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.delivery.DeliveryStatus
import de.openspri.domain.delivery.OutboxEntry
import de.openspri.domain.exchange.Direction
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.Order
import de.openspri.domain.order.OrderHistoryEntry
import de.openspri.domain.order.OrderId
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.Phase
import de.openspri.domain.partner.PartnerId
import java.time.Instant

/** Kinds of events for external systems (D-14: webhooks and event feed). [wireName] is the public name. */
enum class EventType(val wireName: String) {
    ORDER_RECEIVED("order.received"),
    REQUEST_RECEIVED("request.received"),
    MESSAGE_RECEIVED("message.received"),
    ORDER_SUBMITTED("order.submitted"),
    REQUEST_SUBMITTED("request.submitted"),
    MESSAGE_SUBMITTED("message.submitted"),
    /** An outbound request left PENDING: delivered, rejected, unclear, undeliverable, sent by e-mail or cancelled. */
    DELIVERY_UPDATED("delivery.updated"),
    ;

    companion object {
        fun fromWireName(name: String): EventType =
            entries.firstOrNull { it.wireName == name } ?: throw IllegalArgumentException("Unbekannter Ereignistyp '$name' (erlaubt: ${entries.joinToString { it.wireName }})")
    }
}

/**
 * Something external systems may want to react to. Contains identifiers, state and message codes, but no
 * end customer data (principle 13); the order itself is read via the API.
 */
data class BusinessEvent(
    val id: Long?,
    val type: EventType,
    val partnerId: PartnerId,
    val orderId: OrderId?,
    val role: OrderRole?,
    val externalOrderNumber: String?,
    val businessCase: BusinessCase?,
    /** Phase of the order after the event. */
    val phase: Phase?,
    val messageType: MessageType? = null,
    val changeIndicator: ChangeIndicator? = null,
    val codes: List<String> = emptyList(),
    /** Results of the checks, e.g. why an inbound message did not fit the lifecycle. */
    val findings: List<String> = emptyList(),
    val deliveryId: Long? = null,
    val deliveryStatus: DeliveryStatus? = null,
    val at: Instant,
) {
    companion object {
        /** The event for a new history entry of an order ([order] null: no matching order was found). */
        fun of(entry: OrderHistoryEntry, order: Order?, externalOrderNumber: String?): BusinessEvent {
            val inbound = entry.direction == Direction.INBOUND
            val type = when (entry.kind) {
                OrderHistoryEntry.Kind.ORDER -> if (inbound) EventType.ORDER_RECEIVED else EventType.ORDER_SUBMITTED
                OrderHistoryEntry.Kind.REQUEST -> if (inbound) EventType.REQUEST_RECEIVED else EventType.REQUEST_SUBMITTED
                OrderHistoryEntry.Kind.MESSAGE -> if (inbound) EventType.MESSAGE_RECEIVED else EventType.MESSAGE_SUBMITTED
            }
            return BusinessEvent(
                id = null,
                type = type,
                partnerId = entry.partnerId,
                orderId = order?.id,
                role = order?.role,
                externalOrderNumber = order?.data?.externalOrderNumber ?: externalOrderNumber,
                businessCase = order?.data?.businessCase,
                phase = order?.state?.phase,
                messageType = entry.messageType,
                changeIndicator = entry.changeIndicator,
                codes = entry.codes,
                findings = entry.findings,
                at = entry.at,
            )
        }

        fun of(delivery: OutboxEntry, at: Instant) = BusinessEvent(
            id = null,
            type = EventType.DELIVERY_UPDATED,
            partnerId = delivery.partnerId,
            orderId = delivery.orderId,
            role = null,
            externalOrderNumber = delivery.externalOrderNumber,
            businessCase = null,
            phase = null,
            findings = listOfNotNull(delivery.lastError),
            deliveryId = delivery.id,
            deliveryStatus = delivery.status,
            at = at,
        )
    }
}

/** Appends events; called in the transaction of the change, so an event exists exactly if the change does. */
fun interface EventLog {
    fun publish(event: BusinessEvent)

    companion object {
        val NONE = EventLog { }
    }
}

/** The event feed (GET /events?after=). Ids increase monotonically. */
interface EventFeed {
    fun after(id: Long, limit: Int, types: Set<EventType>? = null): List<BusinessEvent>

    /** Id of the newest visible event (0 if none); a new webhook starts after it. */
    fun latestId(): Long

    /** Retention (principle 13): deletes events older than [time]. */
    fun purgeBefore(time: Instant): Int
}

/** A webhook receiver. The secret is used for HMAC-SHA256 and stored encrypted (principle 9). */
data class WebhookSubscription(
    val id: Long?,
    val url: String,
    /** encrypted HMAC secret (AES-256-GCM with the master key) */
    val encryptedSecret: ByteArray,
    /** empty: all types */
    val types: Set<EventType>,
    val enabled: Boolean,
    val description: String?,
    /** id of the last event delivered (or skipped because of [types]) */
    val lastEventId: Long,
    val failures: Int = 0,
    val nextAttemptAt: Instant,
    val lastError: String? = null,
    val lastSuccessAt: Instant? = null,
    val createdAt: Instant,
)

interface WebhookStore {
    fun list(): List<WebhookSubscription>
    fun get(id: Long): WebhookSubscription?
    fun create(subscription: WebhookSubscription): WebhookSubscription
    fun update(id: Long, url: String, types: Set<EventType>, enabled: Boolean, description: String?): Boolean
    fun delete(id: Long): Boolean

    /**
     * Locks one enabled subscription that is due and has newer events, hands it to [deliver] and stores the
     * returned progress. Several instances never deliver to the same subscription at the same time, so the
     * receiver gets the events in order. Returns false if nothing is due.
     */
    fun deliverNext(now: Instant, deliver: (WebhookSubscription) -> WebhookSubscription): Boolean
}
