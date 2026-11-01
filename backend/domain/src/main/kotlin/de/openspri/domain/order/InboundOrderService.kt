package de.openspri.domain.order

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.event.BusinessEvent
import de.openspri.domain.event.EventLog
import de.openspri.domain.exchange.Direction.INBOUND
import de.openspri.domain.message.CodeCheck
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageType
import de.openspri.domain.partner.AgreementRules
import de.openspri.domain.partner.PartnerId
import java.time.Clock
import java.time.LocalDate

/** A business message as received (fields of the message header and attributes). */
data class MessageInfo(
    val type: MessageType,
    val businessCase: BusinessCase,
    val changeIndicator: ChangeIndicator,
    val externalOrderNumber: String,
    val codes: List<String>,
    val contractNumber: String? = null,
    val lineId: String? = null,
)

/** Result of processing one inbound item: the affected order and what the checks found. */
data class ProcessingResult(val orderId: OrderId?, val findings: List<String>)

/**
 * Business processing of accepted inbound orders and messages (after positive TEQ). Inbound processing is
 * lenient (be liberal in what you accept): a message that does not fit the lifecycle is recorded with a
 * finding and does not change the state - the TEQ has already been sent and the partner's view is
 * authoritative for its own messages. Findings of orders are the basis for the Leistungserbringer's answer
 * (QEB or ABBM, M4).
 */
class InboundOrderService(
    private val orders: OrderRepository,
    private val codes: () -> MessageCodeCatalog,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val events: EventLog = EventLog.NONE,
    private val agreements: AgreementRules? = null,
    private val inventory: InventoryBook? = null,
) {

    /** An order of a partner to us (we are Leistungserbringer). */
    fun receiveOrder(partnerId: PartnerId, data: OrderData, archiveId: Long?): ProcessingResult {
        val now = clock.instant()
        val today = LocalDate.now(clock)
        val existing = orders.find(partnerId, OrderRole.LEISTUNGSERBRINGER, data.externalOrderNumber)

        if (data.changeIndicator == ChangeIndicator.STANDARD && !data.complaint) {
            if (existing != null) {
                // specification 11.4: the repeated order is rejected; the original stays unchanged
                val findings = listOf("Dublette (11.4): Auftrag ${data.externalOrderNumber} liegt bereits vor - ABBM 1010")
                record(OrderHistoryEntry(existing.id, partnerId, archiveId, OrderHistoryEntry.Kind.ORDER, findings = findings, at = now, direction = INBOUND), existing, data.externalOrderNumber)
                return ProcessingResult(existing.id, findings)
            }
            val findings = OrderRules.check(data, today).toMutableList()
            agreements?.let { findings += it.checkInbound(partnerId, data, today) }
            data.infrastructureKey?.let { key ->
                if (orders.hasOpenOrder(OrderRole.LEISTUNGSERBRINGER, key, except = null)) {
                    findings += "R-10: Für die technische Infrastruktur liegt bereits ein offener Auftrag vor"
                }
            }
            val state = if (data.businessCase == BusinessCase.KUE_LE) OrderLifecycle.announcedByLeistungserbringer()
            else OrderLifecycle.submitted(data.businessCase, connectivity = data.connectivity, bracketed = data.bracketId != null)
            val saved = orders.save(Order(null, partnerId, OrderRole.LEISTUNGSERBRINGER, data, state, now, now))
            record(OrderHistoryEntry(saved.id, partnerId, archiveId, OrderHistoryEntry.Kind.ORDER, findings = findings, at = now, direction = INBOUND), saved, data.externalOrderNumber)
            return ProcessingResult(saved.id, findings)
        }

        // Storno, Terminverschiebung or Reklamation of an existing order
        val kind = when {
            data.complaint -> RequestKind.REKLAMATION
            data.changeIndicator == ChangeIndicator.STORNO -> RequestKind.STORNO
            else -> RequestKind.TERMINVERSCHIEBUNG
        }
        if (existing == null) {
            // KFT Z.11.2.01: e.g. "Storno" instead of "Standard" in a new order. The record lets the Leistungserbringer
            // answer with ABBM (1007 unzulässiges Änderungskennzeichen); nothing else is possible (OrderLifecycle).
            val findings = listOf(
                "${kind.changeIndicator.code} zu unbekanntem Auftrag ${data.externalOrderNumber}: unzulässiges Änderungskennzeichen, Antwort ABBM",
            )
            val state = OrderLifecycle.requestWithoutOrder(data.businessCase, kind)
            val saved = orders.save(Order(null, partnerId, OrderRole.LEISTUNGSERBRINGER, data.copy(changeIndicator = ChangeIndicator.STANDARD), state, now, now))
            record(OrderHistoryEntry(saved.id, partnerId, archiveId, OrderHistoryEntry.Kind.REQUEST, changeIndicator = kind.changeIndicator, findings = findings, at = now, direction = INBOUND), saved, data.externalOrderNumber)
            return ProcessingResult(saved.id, findings)
        }
        val findings = OrderRules.check(data, today, original = existing.data).toMutableList()
        val updated = when (val transition = OrderLifecycle.apply(existing.state, OrderEvent.Request(kind))) {
            is Transition.Accepted -> {
                val newData = if (kind == RequestKind.TERMINVERSCHIEBUNG && data.newDate != null) {
                    existing.data.copy(desiredDate = data.newDate, ontSerial = data.ontSerial ?: existing.data.ontSerial)
                } else existing.data
                orders.save(existing.copy(state = transition.state, data = newData, updatedAt = now))
            }
            is Transition.Rejected -> { findings += transition.reason; existing }
        }
        record(OrderHistoryEntry(updated.id, partnerId, archiveId, OrderHistoryEntry.Kind.REQUEST, changeIndicator = kind.changeIndicator, findings = findings, at = now, direction = INBOUND), updated, data.externalOrderNumber)
        return ProcessingResult(updated.id, findings)
    }

    /** A business message of a partner to us (we are AG, LE for AG messages, or abgebender EKP). */
    fun receiveMessage(partnerId: PartnerId, message: MessageInfo, archiveId: Long?): ProcessingResult {
        val now = clock.instant()
        val findings = mutableListOf<String>()
        val catalog = codes()
        message.codes.forEach { code ->
            (catalog.check(code, message.type, message.businessCase) as? CodeCheck.Rejected)?.let { findings += "R-13: ${it.reason}" }
        }

        val order: Order? = when (message.type) {
            MessageType.KDLE -> orders.find(partnerId, OrderRole.AUFTRAGGEBER, message.externalOrderNumber)
                ?: orders.save(newOrder(partnerId, OrderRole.AUFTRAGGEBER, message, OrderLifecycle.announcedByLeistungserbringer(), now))
            MessageType.AKM_PV -> orders.find(partnerId, OrderRole.ABGEBENDER_EKP, message.externalOrderNumber)
                ?: orders.save(newOrder(partnerId, OrderRole.ABGEBENDER_EKP, message, OrderLifecycle.handoverAnnounced(), now))
            MessageType.RUEM_PV -> orders.findAnyPartner(OrderRole.LEISTUNGSERBRINGER, message.externalOrderNumber, BusinessCase.PV)
                .let { candidates -> if (candidates.size > 1) findings += "RUEM-PV passt zu ${candidates.size} Aufträgen"; candidates.singleOrNull() }
            MessageType.ZWM_AG, MessageType.TBK_AG, MessageType.ERLM_K ->
                orders.find(partnerId, OrderRole.LEISTUNGSERBRINGER, message.externalOrderNumber)
            MessageType.ABM_PV, MessageType.ERLM_PV, MessageType.ENTM_PV, MessageType.ABBM_PV ->
                orders.find(partnerId, OrderRole.ABGEBENDER_EKP, message.externalOrderNumber)
            else -> orders.find(partnerId, OrderRole.AUFTRAGGEBER, message.externalOrderNumber)
        }
        if (order == null) {
            findings += "${message.type.code} zu unbekanntem Auftrag ${message.externalOrderNumber}"
            record(history(null, partnerId, archiveId, message, findings, now), null, message.externalOrderNumber)
            return ProcessingResult(null, findings)
        }

        val updated = when (val transition = OrderLifecycle.apply(order.state, OrderEvent.Message(message.type, message.changeIndicator, message.codes))) {
            is Transition.Accepted -> {
                // R-15: the LineID is assigned with the first ABM and stays the same (LineID document 3.1.4)
                val data = message.lineId?.takeIf { order.data.lineId == null }?.let { order.data.copy(lineId = it) }
                    ?: message.contractNumber?.takeIf { order.data.contractNumber == null }?.let { order.data.copy(contractNumber = it) }
                    ?: order.data
                orders.save(order.copy(state = transition.state, data = data, updatedAt = now)).also { inventory?.onChange(order, it, now) }
            }
            is Transition.Rejected -> { findings += "Ablauf: ${transition.reason}"; order }
        }
        record(history(updated.id, partnerId, archiveId, message, findings, now), updated, message.externalOrderNumber)
        return ProcessingResult(updated.id, findings)
    }

    /** History entry and event in the same transaction (the inbox item's). */
    private fun record(entry: OrderHistoryEntry, order: Order?, externalOrderNumber: String) {
        orders.append(entry)
        events.publish(BusinessEvent.of(entry, order, externalOrderNumber))
    }

    private fun newOrder(partnerId: PartnerId, role: OrderRole, message: MessageInfo, state: OrderState, now: java.time.Instant) =
        Order(
            null, partnerId, role,
            OrderData(message.externalOrderNumber, message.businessCase, contractNumber = message.contractNumber, lineId = message.lineId),
            state, now, now,
        )

    private fun history(orderId: OrderId?, partnerId: PartnerId, archiveId: Long?, message: MessageInfo, findings: List<String>, now: java.time.Instant) =
        OrderHistoryEntry(orderId, partnerId, archiveId, OrderHistoryEntry.Kind.MESSAGE, message.type, message.changeIndicator, message.codes, findings, now, INBOUND)
}
