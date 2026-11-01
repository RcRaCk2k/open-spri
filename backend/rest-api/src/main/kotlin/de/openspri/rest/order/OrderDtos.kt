package de.openspri.rest.order

import de.openspri.domain.order.Order
import de.openspri.domain.order.OrderEvent
import de.openspri.domain.order.OrderHistoryEntry
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate


@Schema(description = "Ein Auftrag (Übersicht). Der vollständige Auftrag liegt im Nachrichtenarchiv.")
data class OrderDto(
    @field:Schema(requiredMode = REQUIRED) val id: Long,
    @field:Schema(requiredMode = REQUIRED) val partnerId: Long,
    @field:Schema(description = "Unsere Rolle: AUFTRAGGEBER, LEISTUNGSERBRINGER oder ABGEBENDER_EKP", requiredMode = REQUIRED) val role: String,
    @field:Schema(example = "AG-2026-000123", requiredMode = REQUIRED) val externalOrderNumber: String,
    @field:Schema(description = "Geschäftsfall wie in der Spezifikation", example = "NEU", requiredMode = REQUIRED) val businessCase: String,
    @field:Schema(description = "Phase des Zustandsautomaten", example = "CONFIRMED", requiredMode = REQUIRED) val phase: String,
    @field:Schema(requiredMode = REQUIRED) val finished: Boolean,
    @field:Schema(description = "offene Änderungen: STORNO, TERMINVERSCHIEBUNG, REKLAMATION", requiredMode = REQUIRED) val openRequests: List<String>,
    @field:Schema(requiredMode = REQUIRED) val cancelled: Boolean,
    @field:Schema(description = "Austausch mit dem abgebenden EKP beim Providerwechsel") val handoverPhase: String?,
    val clientNumber: String?,
    val serviceNumber: String?,
    val desiredDate: LocalDate?,
    val contractNumber: String?,
    val lineId: String?,
    val homeId: String?,
    @field:Schema(requiredMode = REQUIRED) val products: List<String>,
    @field:Schema(requiredMode = REQUIRED) val createdAt: Instant,
    @field:Schema(requiredMode = REQUIRED) val updatedAt: Instant,
) {
    companion object {
        fun of(order: Order) = OrderDto(
            id = order.id!!.value,
            partnerId = order.partnerId.value,
            role = order.role.name,
            externalOrderNumber = order.data.externalOrderNumber,
            businessCase = order.data.businessCase.code,
            phase = order.state.phase.name,
            finished = order.state.finished,
            openRequests = order.state.openRequests.map { it.name }.sorted(),
            cancelled = order.state.cancelled,
            handoverPhase = order.state.handover?.name,
            clientNumber = order.data.clientNumber,
            serviceNumber = order.data.serviceNumber,
            desiredDate = order.data.desiredDate,
            contractNumber = order.data.contractNumber,
            lineId = order.data.lineId,
            homeId = order.data.homeId,
            products = order.data.products,
            createdAt = order.createdAt,
            updatedAt = order.updatedAt,
        )
    }
}

@Schema(description = "Eine Aktion, die der Zustandsautomat jetzt zulässt")
data class ActionDto(
    @field:Schema(description = "MESSAGE (POST /orders/{id}/messages) oder REQUEST (STORNO: /cancel, TERMINVERSCHIEBUNG: /reschedule, REKLAMATION: /complaint)", requiredMode = REQUIRED) val kind: String,
    @field:Schema(description = "bei MESSAGE: Meldungstyp", example = "ABM") val messageType: String?,
    @field:Schema(description = "bei MESSAGE: Änderungskennzeichen des beantworteten Auftrags", example = "Standard") val changeIndicator: String?,
    @field:Schema(description = "bei REQUEST: STORNO, TERMINVERSCHIEBUNG oder REKLAMATION") val request: String?,
) {
    companion object {
        fun of(event: OrderEvent) = when (event) {
            is OrderEvent.Message -> ActionDto("MESSAGE", event.type.code, event.changeIndicator.code, null)
            is OrderEvent.Request -> ActionDto("REQUEST", null, null, event.kind.name)
        }
    }
}

@Schema(description = "Ein Eintrag im Verlauf: Auftrag, Änderung oder Meldung mit den Befunden der Prüfungen")
data class HistoryDto(
    @field:Schema(description = "ORDER, REQUEST oder MESSAGE", requiredMode = REQUIRED) val kind: String,
    @field:Schema(description = "INBOUND (vom Partner) oder OUTBOUND (von uns)", requiredMode = REQUIRED) val direction: String,
    @field:Schema(description = "eingehend: Id der Rohnachricht (GET /messages/{id}/raw); ausgehend: siehe GET /deliveries?orderId=…") val archiveId: Long?,
    val messageType: String?,
    @field:Schema(requiredMode = REQUIRED) val changeIndicator: String,
    @field:Schema(requiredMode = REQUIRED) val codes: List<String>,
    @field:Schema(description = "Befunde der Prüfungen (z. B. Anlage 1 im Report-Modus)", requiredMode = REQUIRED) val findings: List<String>,
    @field:Schema(requiredMode = REQUIRED) val at: Instant,
) {
    companion object {
        fun of(entry: OrderHistoryEntry) = HistoryDto(
            entry.kind.name, entry.direction.name, entry.archiveId, entry.messageType?.code, entry.changeIndicator.code, entry.codes, entry.findings, entry.at,
        )
    }
}

@Schema(description = "Auftrag mit den jetzt zulässigen Aktionen")
data class OrderDetailDto(
    @field:Schema(requiredMode = REQUIRED) val order: OrderDto,
    @field:Schema(description = "was wir als Nächstes senden dürfen", requiredMode = REQUIRED) val allowedActions: List<ActionDto>,
)

@Schema(description = "Neuer Auftrag an einen Partner (wir sind Auftraggeber)")
data class CreateOrderRequest(
    @field:Schema(requiredMode = REQUIRED) val partnerId: Long,
    @field:Schema(
        description = "Das XSD-Element `auftrag` (Anlage 3, order.xsd) als JSON: Feldnamen wie im XSD, abstrakte Typen mit `@type` " +
            "(z. B. `\"geschaeftsfall\": {\"@type\": \"BereitstellungType\", …}`). Den Kopf (`control`) setzt open-spri.",
        requiredMode = REQUIRED,
    )
    val auftrag: JsonNode,
)

@Schema(description = "Terminverschiebung")
data class RescheduleRequest(
    @field:Schema(description = "neuer Termin (terminNeu)", requiredMode = REQUIRED) val newDate: LocalDate,
)

@Schema(description = "Reklamation einer abgeschlossenen Entstörung")
data class ComplaintRequest(
    @field:Schema(description = "der Entstörauftrag mit `reklamation` (wie bei POST /orders)", requiredMode = REQUIRED) val auftrag: JsonNode,
)

@Schema(description = "Ein Meldungscode; ohne Text wird der Text aus der Meldungscodetabelle übernommen")
data class CodeDto(
    @field:Schema(example = "0000", requiredMode = REQUIRED) val code: String,
    val text: String? = null,
)

@Schema(description = "Eine Meldung zu einem Auftrag. Kopf, externe Auftragsnummer und Auftraggebernummer übernimmt open-spri aus dem Auftrag.")
data class SendMessageRequest(
    @field:Schema(description = "Meldungstyp", example = "ABM", requiredMode = REQUIRED) val type: String,
    @field:Schema(description = "Änderungskennzeichen des Auftrags, auf den die Meldung antwortet", example = "Standard") val changeIndicator: String = "Standard",
    @field:Schema(requiredMode = REQUIRED) val codes: List<CodeDto>,
    @field:Schema(
        description = "weitere `meldungsattribute` des XSD-Typs `Meldungsattribute<Typ>Type`, z. B. " +
            "`{\"verbindlicherLiefertermin\": \"2026-12-01\"}` bei ABM",
    )
    val attributes: JsonNode? = null,
)
