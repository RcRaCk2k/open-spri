package de.openspri.soap

import de.openspri.domain.ChangeIndicator
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.Order
import de.openspri.domain.order.OrderActionRejected
import de.openspri.domain.order.OrderId
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.OutboundOrderService
import de.openspri.domain.order.RequestKind
import de.openspri.domain.partner.PartnerDirectory
import de.openspri.domain.partner.PartnerId
import de.openspri.domain.query.DeliveryQueries
import de.openspri.domain.query.OrderQueries
import tools.jackson.databind.JsonNode
import java.time.LocalDate

/** The order or partner does not exist. REST: 404. */
class NotFoundException(message: String) : RuntimeException(message)

/**
 * Entry point for our own orders and messages from the REST API: builds and checks the S/PRI payload
 * ([OutboundPayloads]) and hands it to [OutboundOrderService], which checks the lifecycle and queues it in
 * the outbox. Nothing is sent directly (principle 7).
 */
class OrderCommands(
    private val payloads: OutboundPayloads,
    private val service: OutboundOrderService,
    private val orders: OrderQueries,
    private val deliveries: DeliveryQueries,
    private val partners: PartnerDirectory,
    private val catalog: () -> MessageCodeCatalog,
) {
    fun submitOrder(partnerId: PartnerId, auftrag: JsonNode): Order {
        val partner = partners.findById(partnerId) ?: throw NotFoundException("Partner ${partnerId.value} existiert nicht")
        if (!partner.enabled) throw OrderActionRejected(listOf("Partner ${partner.name} ist deaktiviert"))
        val (built, data) = payloads.order(auftrag)
        if (data.changeIndicator != ChangeIndicator.STANDARD) {
            throw OrderActionRejected(listOf("Storno und Terminverschiebung über POST /orders/{id}/requests senden"))
        }
        return service.submitOrder(partnerId, data, built.bytes)
    }

    fun request(orderId: OrderId, kind: RequestKind, newDate: LocalDate?, auftrag: JsonNode?): Order {
        val order = order(orderId)
        if (order.role != OrderRole.AUFTRAGGEBER) throw OrderActionRejected(listOf("Nur eigene Aufträge (Rolle AUFTRAGGEBER) können geändert werden"))
        val original = deliveries.originalOrderPayload(orderId)
            ?: throw OrderActionRejected(listOf("Der ursprüngliche Auftrag liegt nicht im Postausgang"))
        val (built, data) = payloads.change(original, kind, newDate, auftrag)
        return service.requestChange(order, kind, data, built.bytes)
    }

    fun message(
        orderId: OrderId,
        type: MessageType,
        changeIndicator: ChangeIndicator,
        codes: List<Pair<String, String?>>,
        attributes: JsonNode?,
    ): Order {
        val order = order(orderId)
        service.precheckMessage(order, type, changeIndicator, codes.map { it.first })
        val (built, info) = payloads.message(order, type, changeIndicator, codes, attributes, catalog())
        return service.sendMessage(order, info, built.bytes)
    }

    fun order(orderId: OrderId): Order = orders.get(orderId) ?: throw NotFoundException("Auftrag ${orderId.value} existiert nicht")
}
