package de.openspri.rest.delivery

import de.openspri.auth.AuditLog
import de.openspri.domain.delivery.DeliveryStatus
import de.openspri.domain.delivery.DeliveryOperations
import de.openspri.domain.delivery.OutboxEntry
import de.openspri.domain.order.OrderId
import de.openspri.domain.query.DeliveryQueries
import de.openspri.domain.query.InboxItem
import de.openspri.domain.query.InboxQueries
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import de.openspri.rest.order.enumValue
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant


@Schema(description = "Eine ausgehende Nachricht im Postausgang")
data class DeliveryDto(
    @field:Schema(requiredMode = REQUIRED) val id: Long,
    @field:Schema(requiredMode = REQUIRED) val partnerId: Long,
    val orderId: Long?,
    @field:Schema(description = "ORDER oder MESSAGE", requiredMode = REQUIRED) val type: String,
    @field:Schema(description = "annehmenAuftrag oder annehmenMeldung", requiredMode = REQUIRED) val operation: String,
    val externalOrderNumber: String?,
    @field:Schema(
        description = "PENDING, DELIVERED, DELIVERED_WITH_ERROR, REJECTED, UNCLEAR, UNDELIVERABLE, SENT_BY_EMAIL, CANCELLED",
        requiredMode = REQUIRED,
    )
    val status: String,
    @field:Schema(requiredMode = REQUIRED) val attempts: Int,
    @field:Schema(requiredMode = REQUIRED) val nextAttemptAt: Instant,
    val lastError: String?,
    @field:Schema(description = "Code der technischen Quittung (TEQ) des Partners") val teqCode: String?,
    @field:Schema(requiredMode = REQUIRED) val createdAt: Instant,
    val finishedAt: Instant?,
) {
    companion object {
        fun of(e: OutboxEntry) = DeliveryDto(
            e.id!!, e.partnerId.value, e.orderId?.value, e.type.name, e.operation.wsdlName, e.externalOrderNumber, e.status.name,
            e.attempts, e.nextAttemptAt, e.lastError, e.teqCode, e.createdAt, e.finishedAt,
        )
    }
}

@Schema(description = "Eine eingegangene Nachricht in der Verarbeitung")
data class InboxDto(
    @field:Schema(requiredMode = REQUIRED) val id: Long,
    @field:Schema(description = "Rohnachricht: GET /messages/{archiveId}/raw", requiredMode = REQUIRED) val archiveId: Long,
    @field:Schema(requiredMode = REQUIRED) val partnerId: Long,
    @field:Schema(requiredMode = REQUIRED) val operation: String,
    val externalOrderNumber: String?,
    @field:Schema(description = "RECEIVED, PROCESSED oder FAILED", requiredMode = REQUIRED) val status: String,
    @field:Schema(requiredMode = REQUIRED) val receivedAt: Instant,
    val processedAt: Instant?,
    val findings: String?,
    val error: String?,
) {
    companion object {
        fun of(i: InboxItem) = InboxDto(
            i.id, i.archiveId, i.partnerId.value, i.operation, i.externalOrderNumber, i.status, i.receivedAt, i.processedAt, i.findings, i.error,
        )
    }
}

@RestController
@RequestMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Zustellung", description = "Postausgang und Eingang überwachen und eingreifen (AUFGABE 5.7, 11.2.3.2)")
class DeliveryController(
    private val deliveries: DeliveryQueries,
    private val operations: DeliveryOperations,
    private val inbox: InboxQueries,
    private val audit: AuditLog,
) {
    @GetMapping("/api/v1/deliveries")
    @PreAuthorize(Requires.DELIVERIES_READ)
    @Operation(summary = "Postausgang", description = "Neueste zuerst. Blättern mit `cursor`.")
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) orderId: Long?,
        @Parameter(description = "Cursor: kleinste `id` der vorigen Seite") @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<DeliveryDto> =
        deliveries.list(status?.let { enumValue<DeliveryStatus>(it, "status") }, orderId?.let(::OrderId), cursor, limit).map(DeliveryDto::of)

    @GetMapping("/api/v1/deliveries/{id}")
    @PreAuthorize(Requires.DELIVERIES_READ)
    @Operation(summary = "Eine ausgehende Nachricht")
    fun get(@PathVariable id: Long): DeliveryDto = DeliveryDto.of(find(id))

    @GetMapping("/api/v1/deliveries/{id}/payload", produces = [MediaType.APPLICATION_XML_VALUE])
    @PreAuthorize("${Requires.DELIVERIES_READ} and ${Requires.ORDERS_READ}")
    @Operation(summary = "Inhalt (unsigniert)", description = "Der Body der SOAP-Nachricht, wie er signiert und gesendet wird. Der Zugriff wird protokolliert.")
    fun payload(@PathVariable id: Long): ByteArray {
        val entry = find(id)
        audit.record(currentActor(), "delivery.payload.read", "delivery:$id")
        return entry.payload
    }

    @PostMapping("/api/v1/deliveries/{id}/retry")
    @PreAuthorize(Requires.DELIVERIES_WRITE)
    @Operation(
        summary = "Erneut senden",
        description = "Für UNDELIVERABLE und für Meldungen im Status UNCLEAR. Ein Auftrag im Status UNCLEAR wird nie mit derselben " +
            "Nummer erneut gesendet (NF-05, 11.2.3.1): mit neuer Auftragsnummer neu stellen und diesen Eintrag zurückziehen (409).",
    )
    fun retry(@PathVariable id: Long): DeliveryDto = act(id, "retry") { operations.retry(id) }

    @PostMapping("/api/v1/deliveries/{id}/sent-by-email")
    @PreAuthorize(Requires.DELIVERIES_WRITE)
    @Operation(summary = "Als per E-Mail versendet markieren", description = "Ersatzweg nach 11.2.3.2; nur für UNDELIVERABLE und UNCLEAR.")
    fun sentByEmail(@PathVariable id: Long): DeliveryDto = act(id, "sent-by-email") { operations.markSentByEmail(id) }

    @PostMapping("/api/v1/deliveries/{id}/cancel")
    @PreAuthorize(Requires.DELIVERIES_WRITE)
    @Operation(summary = "Zurückziehen", description = "Für PENDING, UNDELIVERABLE und UNCLEAR. Gibt die folgenden Nachrichten desselben Reihenfolgeschlüssels frei.")
    fun cancel(@PathVariable id: Long): DeliveryDto = act(id, "cancel") { operations.cancel(id) }

    @GetMapping("/api/v1/inbox")
    @PreAuthorize(Requires.DELIVERIES_READ)
    @Operation(summary = "Eingang", description = "Eingegangene Nachrichten mit Verarbeitungsstatus. FAILED blockiert weitere Nachrichten desselben Auftrags.")
    fun inbox(
        @RequestParam(required = false) status: String?,
        @Parameter(description = "Cursor: kleinste `id` der vorigen Seite") @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<InboxDto> {
        if (status != null) require(status in setOf("RECEIVED", "PROCESSED", "FAILED")) { "status: erlaubt sind RECEIVED, PROCESSED, FAILED" }
        return inbox.list(status, cursor, limit).map(InboxDto::of)
    }

    @PostMapping("/api/v1/inbox/{id}/reprocess")
    @PreAuthorize(Requires.DELIVERIES_WRITE)
    @Operation(summary = "Fehlgeschlagene Verarbeitung wiederholen", description = "Nur für FAILED.")
    fun reprocess(@PathVariable id: Long) {
        check(inbox.reprocess(id)) { "Eingang $id ist nicht im Status FAILED" }
        audit.record(currentActor(), "inbox.reprocess", "inbox:$id")
    }

    private fun find(id: Long) = deliveries.get(id) ?: throw NoSuchElementException("Nachricht $id existiert nicht")

    private fun act(id: Long, action: String, change: () -> OutboxEntry): DeliveryDto {
        val entry = change()
        audit.record(currentActor(), "delivery.$action", "delivery:$id")
        return DeliveryDto.of(entry)
    }
}
