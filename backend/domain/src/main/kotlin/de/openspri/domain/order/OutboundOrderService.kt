package de.openspri.domain.order

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.delivery.Outbox
import de.openspri.domain.delivery.OutboxEntry
import de.openspri.domain.delivery.RequestType
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.message.CodeCheck
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageType
import de.openspri.domain.message.Party
import de.openspri.domain.partner.PartnerId
import java.time.Clock
import java.time.LocalDate

/** A request we want to send is not allowed; the reason names the rule (REST: 409/422). */
class OrderActionRejected(val reasons: List<String>) : RuntimeException(reasons.joinToString("; "))

/** Runs a block in one database transaction (implemented by module persistence). */
interface Transactions {
    fun <T> run(block: () -> T): T
}

/**
 * Our own orders and messages (AUFGABE chapter 5.5/5.6). Unlike inbound processing this is strict: a request
 * that the lifecycle, the content rules or the message code table do not allow is rejected and never queued.
 * State change and outbox entry are written in the same transaction (transactional outbox).
 */
class OutboundOrderService(
    private val orders: OrderRepository,
    private val outbox: Outbox,
    private val codes: () -> MessageCodeCatalog,
    private val transactions: Transactions,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    /** We order from a partner (we are Auftraggeber). [payload] is the annehmenAuftragRequest element. */
    fun submitOrder(partnerId: PartnerId, data: OrderData, payload: ByteArray): Order = transactions.run {
        require(data.changeIndicator == ChangeIndicator.STANDARD) { "use requestChange for Storno and Terminverschiebung" }
        if (orders.find(partnerId, OrderRole.AUFTRAGGEBER, data.externalOrderNumber) != null) {
            throw OrderActionRejected(listOf("Die externe Auftragsnummer ${data.externalOrderNumber} ist bereits vergeben (11.4)"))
        }
        val findings = OrderRules.check(data, LocalDate.now(clock))
        if (findings.isNotEmpty()) throw OrderActionRejected(findings)
        val now = clock.instant()
        val state = OrderLifecycle.submitted(data.businessCase, connectivity = data.connectivity, bracketed = data.bracketId != null)
        val order = orders.save(Order(null, partnerId, OrderRole.AUFTRAGGEBER, data, state, now, now, sequenceKey = keyFor(partnerId, data)))
        enqueue(order, RequestType.ORDER, SoapOperation.ANNEHMEN_AUFTRAG, payload)
        orders.append(OrderHistoryEntry(order.id, partnerId, null, OrderHistoryEntry.Kind.ORDER, at = now))
        order
    }

    /** Storno, Terminverschiebung or Reklamation of our own order. [data] is the content of the change order. */
    fun requestChange(order: Order, kind: RequestKind, data: OrderData, payload: ByteArray): Order = transactions.run {
        check(order.role == OrderRole.AUFTRAGGEBER) { "only our own orders can be changed" }
        val findings = OrderRules.check(data, LocalDate.now(clock), original = order.data).toMutableList()
        val transition = OrderLifecycle.apply(order.state, OrderEvent.Request(kind))
        if (transition is Transition.Rejected) findings += transition.reason
        if (findings.isNotEmpty()) throw OrderActionRejected(findings)
        val now = clock.instant()
        val newData = if (kind == RequestKind.TERMINVERSCHIEBUNG && data.newDate != null) order.data.copy(desiredDate = data.newDate) else order.data
        val saved = orders.save(order.copy(state = (transition as Transition.Accepted).state, data = newData, updatedAt = now, sequenceKey = order.sequenceKey ?: keyFor(order.partnerId, order.data)))
        enqueue(saved, RequestType.ORDER, SoapOperation.ANNEHMEN_AUFTRAG, payload)
        orders.append(OrderHistoryEntry(saved.id, order.partnerId, null, OrderHistoryEntry.Kind.REQUEST, changeIndicator = kind.changeIndicator, at = now))
        saved
    }

    /**
     * A business message to the partner: as Leistungserbringer (QEB, ABM, …), as Auftraggeber (ZWM-AG, TBK-AG,
     * ERLM-K) or as abgebender EKP (RUEM-PV). [payload] is the annehmenMeldungRequest element.
     */
    fun sendMessage(order: Order, message: MessageInfo, payload: ByteArray): Order = transactions.run {
        val reasons = mutableListOf<String>()
        val sender = message.type.sender
        val allowedSender = when (order.role) {
            OrderRole.LEISTUNGSERBRINGER -> Party.LEISTUNGSERBRINGER
            OrderRole.AUFTRAGGEBER -> Party.AUFTRAGGEBER
            OrderRole.ABGEBENDER_EKP -> Party.ABGEBENDER_EKP
        }
        if (sender != allowedSender) reasons += "${message.type.code} wird vom ${sender.name} gesendet, wir sind ${order.role.name}"
        if (message.businessCase != order.data.businessCase && !(order.data.businessCase == BusinessCase.KUE_LE)) {
            reasons += "Geschäftsfall ${message.businessCase.code} passt nicht zum Auftrag (${order.data.businessCase.code})"
        }
        val catalog = codes()
        message.codes.forEach { code ->
            (catalog.check(code, message.type, message.businessCase) as? CodeCheck.Rejected)?.let { reasons += "R-13: ${it.reason}" }
        }
        if (message.codes.isEmpty()) reasons += "Eine Meldung braucht mindestens einen Meldungscode (11.2.4)"
        val transition = OrderLifecycle.apply(order.state, OrderEvent.Message(message.type, message.changeIndicator, message.codes))
        if (transition is Transition.Rejected) reasons += transition.reason
        if (reasons.isNotEmpty()) throw OrderActionRejected(reasons)

        val now = clock.instant()
        val data = message.lineId?.takeIf { order.data.lineId == null }?.let { order.data.copy(lineId = it) } ?: order.data
        // the key is fixed with the first request of the order: a LineID assigned later (ABM) must not change it
        val saved = orders.save(order.copy(state = (transition as Transition.Accepted).state, data = data, updatedAt = now, sequenceKey = order.sequenceKey ?: keyFor(order.partnerId, order.data)))
        enqueue(saved, RequestType.MESSAGE, SoapOperation.ANNEHMEN_MELDUNG, payload)
        orders.append(OrderHistoryEntry(saved.id, order.partnerId, null, OrderHistoryEntry.Kind.MESSAGE, message.type, message.changeIndicator, message.codes, at = now))
        saved
    }

    /**
     * Ordering key (AUFGABE 8.3): requests concerning the same line (known LineID) are ordered across orders,
     * otherwise per order. Fixed for the life of the order.
     */
    private fun keyFor(partnerId: PartnerId, data: OrderData) = OutboxEntry.sequenceKey(partnerId, data.lineId, data.externalOrderNumber)

    private fun enqueue(order: Order, type: RequestType, operation: SoapOperation, payload: ByteArray) {
        val now = clock.instant()
        outbox.enqueue(
            OutboxEntry(
                id = null,
                partnerId = order.partnerId,
                orderId = order.id,
                type = type,
                operation = operation,
                externalOrderNumber = order.data.externalOrderNumber,
                sequenceKey = requireNotNull(order.sequenceKey),
                payload = payload,
                nextAttemptAt = now,
                createdAt = now,
            ),
        )
    }

}
