package de.openspri.rest.event

import de.openspri.domain.event.BusinessEvent
import de.openspri.domain.event.EventFeed
import de.openspri.domain.event.EventType
import de.openspri.rest.auth.Requires
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@Schema(description = "Ein Ereignis. Enthält Kennungen, Status und Meldungscodes, aber keine Endkundendaten; den Auftrag liest man über GET /orders/{orderId}.")
data class EventDto(
    @field:Schema(description = "streng steigend; Cursor für `after`", requiredMode = REQUIRED) val id: Long,
    @field:Schema(
        description = "order.received, request.received, message.received, order.submitted, request.submitted, message.submitted, delivery.updated",
        example = "message.received",
        requiredMode = REQUIRED,
    )
    val type: String,
    @field:Schema(requiredMode = REQUIRED) val partnerId: Long,
    val orderId: Long?,
    @field:Schema(description = "unsere Rolle im Auftrag") val role: String?,
    val externalOrderNumber: String?,
    val businessCase: String?,
    @field:Schema(description = "Phase des Auftrags nach dem Ereignis") val phase: String?,
    @field:Schema(example = "TAM") val messageType: String?,
    @field:Schema(example = "Standard") val changeIndicator: String?,
    @field:Schema(requiredMode = REQUIRED) val codes: List<String>,
    @field:Schema(description = "Befunde der Prüfungen, z. B. warum eine Meldung nicht zum Ablauf passte", requiredMode = REQUIRED) val findings: List<String>,
    @field:Schema(description = "bei delivery.updated: Id im Postausgang") val deliveryId: Long?,
    @field:Schema(description = "bei delivery.updated: neuer Zustellstatus") val deliveryStatus: String?,
    @field:Schema(requiredMode = REQUIRED) val at: Instant,
) {
    companion object {
        fun of(e: BusinessEvent) = EventDto(
            e.id!!, e.type.wireName, e.partnerId.value, e.orderId?.value, e.role?.name, e.externalOrderNumber, e.businessCase?.code,
            e.phase?.name, e.messageType?.code, e.changeIndicator?.code, e.codes, e.findings, e.deliveryId, e.deliveryStatus?.name, e.at,
        )
    }
}

@RestController
@RequestMapping("/api/v1/events", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Ereignisse", description = "Event-Feed per Polling und Webhooks (D-14)")
class EventController(private val feed: EventFeed) {

    @GetMapping
    @PreAuthorize(Requires.EVENTS_READ)
    @Operation(
        summary = "Ereignisse nach einer Id",
        description = "Älteste zuerst. Der Client merkt sich die größte `id` und fragt mit `after=<id>` weiter. " +
            "Ereignisse erscheinen mit etwa 2 s Verzögerung, damit keines übersprungen wird, das gerade noch gespeichert wird.",
    )
    fun after(
        @Parameter(description = "Cursor: größte `id` der vorigen Antwort, 0 für den Anfang") @RequestParam(defaultValue = "0") after: Long,
        @RequestParam(defaultValue = "100") limit: Int,
        @Parameter(description = "nur diese Typen, kommagetrennt") @RequestParam(required = false) types: List<String>?,
    ): List<EventDto> =
        feed.after(after, limit.coerceIn(1, 1000), types?.map(EventType::fromWireName)?.toSet()).map(EventDto::of)
}
