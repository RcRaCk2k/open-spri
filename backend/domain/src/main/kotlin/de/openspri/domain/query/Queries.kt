package de.openspri.domain.query

import de.openspri.domain.BusinessCase
import de.openspri.domain.delivery.DeliveryStatus
import de.openspri.domain.delivery.OutboxEntry
import de.openspri.domain.exchange.ArchivedExchange
import de.openspri.domain.order.HandoverPhase
import de.openspri.domain.order.Order
import de.openspri.domain.order.OrderHistoryEntry
import de.openspri.domain.order.OrderId
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.Phase
import de.openspri.domain.partner.PartnerId
import java.time.Instant

/** Read access for the REST API and the frontend. Write access stays with the domain services. */
data class OrderFilter(
    val role: OrderRole? = null,
    val partnerId: PartnerId? = null,
    val businessCase: BusinessCase? = null,
    val phase: Phase? = null,
    /** provider change, view of the abgebender EKP: e.g. ANNOUNCED = RUEM-PV outstanding */
    val handover: HandoverPhase? = null,
    /** external order number, LineID or contract number (prefix search) */
    val text: String? = null,
    /** only open (unfinished) orders */
    val openOnly: Boolean = false,
    /** cursor: orders with a smaller id (newest first) */
    val beforeId: Long? = null,
    val limit: Int = 50,
)

interface OrderQueries {
    fun get(id: OrderId): Order?
    fun search(filter: OrderFilter): List<Order>
    fun history(id: OrderId): List<OrderHistoryEntry>
}

interface DeliveryQueries {
    fun get(id: Long): OutboxEntry?
    fun list(status: DeliveryStatus?, orderId: OrderId?, beforeId: Long?, limit: Int): List<OutboxEntry>

    /** The payload of the first order request of an order (basis for Storno and Terminverschiebung). */
    fun originalOrderPayload(orderId: OrderId): ByteArray?
}

data class InboxItem(
    val id: Long,
    val archiveId: Long,
    val partnerId: PartnerId,
    val operation: String,
    val externalOrderNumber: String?,
    val status: String,
    val receivedAt: Instant,
    val processedAt: Instant?,
    val findings: String?,
    val error: String?,
)

interface InboxQueries {
    fun list(status: String?, beforeId: Long?, limit: Int): List<InboxItem>
    fun reprocess(id: Long): Boolean
}

interface ArchiveQueries {
    fun get(id: Long): ArchivedExchange?
}
