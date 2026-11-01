package de.openspri.domain.order

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.delivery.Outbox
import de.openspri.domain.delivery.OutboxEntry
import de.openspri.domain.delivery.RequestType
import de.openspri.domain.event.BusinessEvent
import de.openspri.domain.event.EventLog
import de.openspri.domain.exchange.Direction.OUTBOUND
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.message.CodeCheck
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageType
import de.openspri.domain.message.Party
import de.openspri.domain.partner.AgreementRules
import de.openspri.domain.partner.PartnerId
import java.time.Clock
import java.time.LocalDate

/**
 * A request we want to send is not allowed; the reason names the rule (REST: 409). [allowed] lists what the
 * lifecycle permits instead (principle 6), empty if the order does not exist yet.
 */
class OrderActionRejected(val reasons: List<String>, val allowed: List<OrderEvent> = emptyList()) : RuntimeException(reasons.joinToString("; "))

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
    private val events: EventLog = EventLog.NONE,
    private val drafts: OrderDrafts? = null,
    private val agreements: AgreementRules? = null,
) {

    /** We order from a partner (we are Auftraggeber). [payload] is the annehmenAuftragRequest element. */
    fun submitOrder(partnerId: PartnerId, data: OrderData, payload: ByteArray): Order = transactions.run {
        requireNewNumber(partnerId, data, except = null)
        val now = clock.instant()
        send(Order(null, partnerId, OrderRole.AUFTRAGGEBER, data, draftState(data), now, now), data, payload)
    }

    /**
     * Saves our order as a draft (AUFGABE 5.6: Entwurf → Senden) or replaces the content of [existing].
     * The external order number is reserved; content and order rules are checked again when it is sent.
     */
    fun saveDraft(partnerId: PartnerId, data: OrderData, content: ByteArray, existing: Order? = null): Order = transactions.run {
        val store = requireNotNull(drafts) { "no draft store configured" }
        if (existing != null && existing.state.phase != Phase.DRAFT) {
            throw OrderActionRejected(listOf("Der Auftrag ist bereits gesendet und kein Entwurf mehr"), allowedActions(existing))
        }
        requireNewNumber(partnerId, data, except = existing?.id)
        val now = clock.instant()
        val saved = orders.save(
            existing?.copy(data = data, state = draftState(data), updatedAt = now)
                ?: Order(null, partnerId, OrderRole.AUFTRAGGEBER, data, draftState(data), now, now),
        )
        store.put(requireNotNull(saved.id), content)
        saved
    }

    fun draftContent(order: Order): ByteArray? = drafts?.get(requireNotNull(order.id))

    /** Sends a draft: the same checks as [submitOrder], then DRAFT → SUBMITTED and into the outbox. */
    fun sendDraft(order: Order, data: OrderData, payload: ByteArray): Order = transactions.run {
        if (order.state.phase != Phase.DRAFT) throw OrderActionRejected(listOf("Nur ein Entwurf kann gesendet werden"), allowedActions(order))
        if (data.externalOrderNumber != order.data.externalOrderNumber) {
            throw OrderActionRejected(listOf("Die externe Auftragsnummer des Entwurfs hat sich geändert; Entwurf zuerst speichern"))
        }
        val sent = send(order.copy(data = data, state = draftState(data)), data, payload)
        drafts?.remove(requireNotNull(order.id))
        sent
    }

    /** Deletes a draft. A sent order cannot be deleted (it is ended by Storno or by the partner). */
    fun deleteDraft(order: Order) = transactions.run {
        if (order.state.phase != Phase.DRAFT) throw OrderActionRejected(listOf("Nur ein Entwurf kann gelöscht werden"), allowedActions(order))
        drafts?.remove(requireNotNull(order.id))
        orders.deleteDraft(requireNotNull(order.id))
    }

    /** [draft] may be unsaved (direct submit) or a stored draft. Checks first, so nothing is stored on rejection. */
    private fun send(draft: Order, data: OrderData, payload: ByteArray): Order {
        require(data.changeIndicator == ChangeIndicator.STANDARD) { "use requestChange for Storno and Terminverschiebung" }
        val today = LocalDate.now(clock)
        val findings = OrderRules.check(data, today) + agreements?.checkProject(draft.partnerId, data, today).orEmpty()
        if (findings.isNotEmpty()) throw OrderActionRejected(findings)
        val transition = OrderLifecycle.apply(draft.state, OrderEvent.Send) as Transition.Accepted
        val now = clock.instant()
        val order = orders.save(draft.copy(state = transition.state, updatedAt = now, sequenceKey = keyFor(draft.partnerId, data)))
        enqueue(order, RequestType.ORDER, SoapOperation.ANNEHMEN_AUFTRAG, payload)
        record(OrderHistoryEntry(order.id, order.partnerId, null, OrderHistoryEntry.Kind.ORDER, at = now, direction = OUTBOUND), order)
        return order
    }

    private fun draftState(data: OrderData) =
        OrderLifecycle.draft(data.businessCase, connectivity = data.connectivity, bracketed = data.bracketId != null)

    private fun requireNewNumber(partnerId: PartnerId, data: OrderData, except: OrderId?) {
        val existing = orders.find(partnerId, OrderRole.AUFTRAGGEBER, data.externalOrderNumber)
        if (existing != null && existing.id != except) {
            throw OrderActionRejected(listOf("Die externe Auftragsnummer ${data.externalOrderNumber} ist bereits vergeben (11.4)"))
        }
    }

    /**
     * Kündigung durch den Leistungserbringer (KUE-LE, Arbeitshandbuch: "eigenständige Meldung, KEIN Auftrag"):
     * we create the order record ourselves and send the KDLE; ERLM and ENTM follow on the same record.
     */
    fun terminate(partnerId: PartnerId, data: OrderData, message: MessageInfo, payload: ByteArray): Order = transactions.run {
        require(data.businessCase == BusinessCase.KUE_LE && message.type == MessageType.KDLE)
        if (orders.find(partnerId, OrderRole.LEISTUNGSERBRINGER, data.externalOrderNumber) != null) {
            throw OrderActionRejected(listOf("Die externe Auftragsnummer ${data.externalOrderNumber} ist bereits vergeben (11.4)"))
        }
        val now = clock.instant()
        val draft = Order(null, partnerId, OrderRole.LEISTUNGSERBRINGER, data, OrderLifecycle.announcedByLeistungserbringer(), now, now)
        precheckMessage(draft, message.type, message.changeIndicator, message.codes)
        sendMessage(orders.save(draft), message, payload)
    }

    /** Storno, Terminverschiebung or Reklamation of our own order. [data] is the content of the change order. */
    fun requestChange(order: Order, kind: RequestKind, data: OrderData, payload: ByteArray): Order = transactions.run {
        check(order.role == OrderRole.AUFTRAGGEBER) { "only our own orders can be changed" }
        val findings = OrderRules.check(data, LocalDate.now(clock), original = order.data).toMutableList()
        val transition = OrderLifecycle.apply(order.state, OrderEvent.Request(kind))
        if (transition is Transition.Rejected) findings += transition.reason
        if (findings.isNotEmpty()) throw OrderActionRejected(findings, allowedActions(order))
        val now = clock.instant()
        val newData = if (kind == RequestKind.TERMINVERSCHIEBUNG && data.newDate != null) order.data.copy(desiredDate = data.newDate) else order.data
        val saved = orders.save(order.copy(state = (transition as Transition.Accepted).state, data = newData, updatedAt = now, sequenceKey = order.sequenceKey ?: keyFor(order.partnerId, order.data)))
        enqueue(saved, RequestType.ORDER, SoapOperation.ANNEHMEN_AUFTRAG, payload)
        record(OrderHistoryEntry(saved.id, order.partnerId, null, OrderHistoryEntry.Kind.REQUEST, changeIndicator = kind.changeIndicator, at = now, direction = OUTBOUND), saved)
        saved
    }

    /**
     * A business message to the partner: as Leistungserbringer (QEB, ABM, …), as Auftraggeber (ZWM-AG, TBK-AG,
     * ERLM-K) or as abgebender EKP (RUEM-PV). [payload] is the annehmenMeldungRequest element.
     */
    fun sendMessage(order: Order, message: MessageInfo, payload: ByteArray): Order = transactions.run {
        val (reasons, transition) = messageChecks(order, message.type, message.changeIndicator, message.codes, message.businessCase)
        if (reasons.isNotEmpty()) throw OrderActionRejected(reasons, allowedActions(order))

        val now = clock.instant()
        val data = message.lineId?.takeIf { order.data.lineId == null }?.let { order.data.copy(lineId = it) } ?: order.data
        // the key is fixed with the first request of the order: a LineID assigned later (ABM) must not change it
        val saved = orders.save(order.copy(state = (transition as Transition.Accepted).state, data = data, updatedAt = now, sequenceKey = order.sequenceKey ?: keyFor(order.partnerId, order.data)))
        enqueue(saved, RequestType.MESSAGE, SoapOperation.ANNEHMEN_MELDUNG, payload)
        record(OrderHistoryEntry(saved.id, order.partnerId, null, OrderHistoryEntry.Kind.MESSAGE, message.type, message.changeIndicator, message.codes, at = now, direction = OUTBOUND), saved)
        saved
    }

    /**
     * Checks role, message codes and lifecycle before the payload is built, so that a message the order does
     * not allow is rejected with the allowed actions (409) rather than with content errors.
     */
    fun precheckMessage(order: Order, type: MessageType, changeIndicator: ChangeIndicator, codes: List<String>) {
        val (reasons, _) = messageChecks(order, type, changeIndicator, codes, order.data.businessCase)
        if (reasons.isNotEmpty()) throw OrderActionRejected(reasons, allowedActions(order))
    }

    private fun messageChecks(
        order: Order,
        type: MessageType,
        changeIndicator: ChangeIndicator,
        codes: List<String>,
        businessCase: BusinessCase,
    ): Pair<List<String>, Transition> {
        val reasons = mutableListOf<String>()
        if (type.sender != ourParty(order.role)) reasons += "${type.code} wird vom ${type.sender.name} gesendet, wir sind ${order.role.name}"
        if (businessCase != order.data.businessCase && order.data.businessCase != BusinessCase.KUE_LE) {
            reasons += "Geschäftsfall ${businessCase.code} passt nicht zum Auftrag (${order.data.businessCase.code})"
        }
        val catalog = this.codes()
        codes.forEach { code ->
            (catalog.check(code, type, businessCase) as? CodeCheck.Rejected)?.let { reasons += "R-13: ${it.reason}" }
        }
        if (codes.isEmpty()) reasons += "Eine Meldung braucht mindestens einen Meldungscode (11.2.4)"
        val transition = OrderLifecycle.apply(order.state, OrderEvent.Message(type, changeIndicator, codes))
        if (transition is Transition.Rejected) reasons += transition.reason
        return reasons to transition
    }

    /**
     * What we may send next for [order]: lifecycle transitions whose sender is our role (principle 6:
     * REST answers 409 with this list when an action is not allowed).
     */
    fun allowedActions(order: Order): List<OrderEvent> = OrderLifecycle.allowedEvents(order.state).filter { event ->
        when (event) {
            is OrderEvent.Request, OrderEvent.Send -> order.role == OrderRole.AUFTRAGGEBER
            is OrderEvent.Message -> event.type.sender == ourParty(order.role)
        }
    }

    private fun ourParty(role: OrderRole) = when (role) {
        OrderRole.LEISTUNGSERBRINGER -> Party.LEISTUNGSERBRINGER
        OrderRole.AUFTRAGGEBER -> Party.AUFTRAGGEBER
        OrderRole.ABGEBENDER_EKP -> Party.ABGEBENDER_EKP
    }

    private fun record(entry: OrderHistoryEntry, order: Order) {
        orders.append(entry)
        events.publish(BusinessEvent.of(entry, order, order.data.externalOrderNumber))
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
