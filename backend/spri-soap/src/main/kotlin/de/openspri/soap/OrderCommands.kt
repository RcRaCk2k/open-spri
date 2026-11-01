package de.openspri.soap

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.Order
import de.openspri.domain.order.OrderActionRejected
import de.openspri.domain.order.OrderData
import de.openspri.domain.order.OrderId
import de.openspri.domain.order.OrderLifecycle
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.OutboundOrderService
import de.openspri.domain.order.RequestKind
import de.openspri.domain.partner.PartnerDirectory
import de.openspri.domain.partner.PartnerId
import de.openspri.domain.query.ArchiveQueries
import de.openspri.domain.query.DeliveryQueries
import de.openspri.domain.query.OrderQueries
import de.telekom.spri.oss.v4.envelope.AnnehmenMeldungRequestType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.LocalDate

/** The order or partner does not exist. REST: 404. */
class NotFoundException(message: String) : RuntimeException(message)

/** Kündigung durch den Leistungserbringer (KDLE, KUE-LE): the attributes of `MeldungsattributeKDLEType`. */
data class Termination(
    val externalOrderNumber: String,
    val contractNumber: String,
    val clientNumber: String,
    val shutdownDate: LocalDate,
    val reason: String? = null,
    /** not part of the KDLE; orders the outbox with other requests for the same line and helps to find the order */
    val lineId: String? = null,
)

/** RUEM-PV: answer of the abgebender EKP (`ProviderwechselRueckmeldungType`). */
data class ProviderChangeReply(
    val consent: Boolean,
    val providerName: String? = null,
    val answerCode: String? = null,
    val answerText: String? = null,
)

/** What the aufnehmender EKP announced with the AKM-PV (`aufnehmenderProvider`, `anschluss`). */
data class ProviderChangeAnnouncement(
    val archiveId: Long,
    val providerName: String?,
    val plannedTransferDate: LocalDate?,
    /** `antwortfrist`: the RUEM-PV is due by this date */
    val replyDeadline: LocalDate?,
    val lineId: String?,
)

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
    private val archive: ArchiveQueries? = null,
) {
    private val json = JsonMapper.builder().build()

    fun submitOrder(partnerId: PartnerId, auftrag: JsonNode): Order {
        enabledPartner(partnerId)
        val (built, data) = newOrder(auftrag)
        return service.submitOrder(partnerId, data, built.bytes)
    }

    /** Saves a draft. It must already be a valid order (XSD, Anlage 1); the order rules are checked when it is sent. */
    fun saveDraft(partnerId: PartnerId, auftrag: JsonNode, existing: OrderId? = null): Order {
        enabledPartner(partnerId)
        val order = existing?.let(::order)
        if (order != null && order.partnerId != partnerId) throw OrderActionRejected(listOf("Der Partner eines Entwurfs kann nicht geändert werden"))
        val (_, data) = newOrder(auftrag)
        return service.saveDraft(partnerId, data, auftrag.toString().toByteArray(), order)
    }

    fun draft(orderId: OrderId): JsonNode? = service.draftContent(order(orderId))?.let { json.readTree(it) }

    /** Builds the payload again from the draft (fresh control header and current key) and sends it. */
    fun sendDraft(orderId: OrderId): Order {
        val order = order(orderId)
        val content = service.draftContent(order)
            ?: throw OrderActionRejected(listOf("Nur ein Entwurf kann gesendet werden"), service.allowedActions(order))
        enabledPartner(order.partnerId)
        val (built, data) = newOrder(json.readTree(content))
        return service.sendDraft(order, data, built.bytes)
    }

    fun deleteDraft(orderId: OrderId) = service.deleteDraft(order(orderId))

    /** KDLE without a prior order (AUFGABE 5.5): we are Leistungserbringer and create the KUE-LE order. */
    fun terminate(partnerId: PartnerId, termination: Termination, codes: List<Pair<String, String?>>, attributes: JsonNode?): Order {
        enabledPartner(partnerId)
        val data = OrderData(
            termination.externalOrderNumber, BusinessCase.KUE_LE,
            clientNumber = termination.clientNumber, contractNumber = termination.contractNumber, lineId = termination.lineId,
        )
        val order = Order(null, partnerId, OrderRole.LEISTUNGSERBRINGER, data, OrderLifecycle.announcedByLeistungserbringer(), Instant.EPOCH, Instant.EPOCH)
        service.precheckMessage(order, MessageType.KDLE, ChangeIndicator.STANDARD, codes.map { it.first })
        val node = (attributes?.deepCopy() as? ObjectNode) ?: JsonNodeFactory.instance.objectNode()
        node.put("abschaltungstermin", termination.shutdownDate.toString())
        termination.reason?.let { node.put("abschaltungsgrund", it) }
        val (built, info) = payloads.message(order, MessageType.KDLE, ChangeIndicator.STANDARD, codes, node, catalog())
        return service.terminate(partnerId, data, info, built.bytes)
    }

    /**
     * RUEM-PV to an AKM-PV we received as abgebender EKP. Contract, order numbers, VorabstimmungsID and
     * `anschluss` are taken from the archived AKM-PV, so only the answer itself has to be given.
     */
    fun replyToProviderChange(orderId: OrderId, reply: ProviderChangeReply, codes: List<Pair<String, String?>>): Order {
        val order = order(orderId)
        if (order.role != OrderRole.ABGEBENDER_EKP) throw NotFoundException("Providerwechsel-Anfrage ${orderId.value} existiert nicht")
        service.precheckMessage(order, MessageType.RUEM_PV, ChangeIndicator.STANDARD, codes.map { it.first })
        val (_, announcement) = announcementAttributes(orderId)
            ?: throw OrderActionRejected(listOf("Die AKM-PV zu diesem Auftrag liegt nicht im Nachrichtenarchiv"))
        val attributes = payloads.providerChangeReply(announcement, reply)
        val (built, info) = payloads.message(order, MessageType.RUEM_PV, ChangeIndicator.STANDARD, codes, attributes, catalog())
        return service.sendMessage(order, info, built.bytes)
    }

    fun providerChangeAnnouncement(orderId: OrderId): ProviderChangeAnnouncement? {
        val (archiveId, attributes) = announcementAttributes(orderId) ?: return null
        val provider = attributes.path("aufnehmenderProvider")
        fun date(node: JsonNode) = node.stringValue(null)?.let { LocalDate.parse(it.take(10)) }
        return ProviderChangeAnnouncement(
            archiveId,
            provider.path("providername").stringValue(null),
            date(provider.path("uebernahmedatumGeplant")),
            date(provider.path("antwortfrist")),
            attributes.path("anschluss").path("lineId").stringValue(null),
        )
    }

    /** `meldungsattribute` of the last archived AKM-PV of this order, as JSON. */
    private fun announcementAttributes(orderId: OrderId): Pair<Long, JsonNode>? {
        val archiveId = orders.history(orderId).lastOrNull { it.messageType == MessageType.AKM_PV && it.archiveId != null }?.archiveId ?: return null
        val raw = archive?.get(archiveId)?.request ?: return null
        val payload = SoapEnvelope.payload(SecureXml.parse(raw)) ?: return null
        val meldung = SpriJson.toJson(SpriJaxb.unmarshal(payload, AnnehmenMeldungRequestType::class.java).meldung)
        return archiveId to meldung.path("meldungstyp").path("meldungsattribute")
    }

    private fun enabledPartner(partnerId: PartnerId) {
        val partner = partners.findById(partnerId) ?: throw NotFoundException("Partner ${partnerId.value} existiert nicht")
        if (!partner.enabled) throw OrderActionRejected(listOf("Partner ${partner.name} ist deaktiviert"))
    }

    private fun newOrder(auftrag: JsonNode) = payloads.order(auftrag).also { (_, data) ->
        if (data.changeIndicator != ChangeIndicator.STANDARD) {
            throw OrderActionRejected(listOf("Storno und Terminverschiebung über POST /orders/{id}/cancel bzw. /reschedule senden"))
        }
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
