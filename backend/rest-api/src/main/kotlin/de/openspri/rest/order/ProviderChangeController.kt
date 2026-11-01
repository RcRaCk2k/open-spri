package de.openspri.rest.order

import de.openspri.auth.AuditLog
import de.openspri.domain.order.HandoverPhase
import de.openspri.domain.order.OrderId
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.OutboundOrderService
import de.openspri.domain.partner.PartnerId
import de.openspri.domain.query.OrderFilter
import de.openspri.domain.query.OrderQueries
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import de.openspri.soap.OrderCommands
import de.openspri.soap.ProviderChangeReply
import de.openspri.soap.Termination
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Processes that start without an order of ours: KDLE as Leistungserbringer, AKM-PV as abgebender EKP (AUFGABE 5.5, 3). */
@RestController
@RequestMapping("/api/v1", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Aufträge", description = "Aufträge in allen Rollen, Änderungen und Meldungen (AUFGABE 5.5/5.6)")
class ProviderChangeController(
    private val orders: OrderQueries,
    private val commands: OrderCommands,
    private val service: OutboundOrderService,
    private val audit: AuditLog,
) {
    @PostMapping("/terminations", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(Requires.LE_MESSAGES_WRITE)
    @Operation(
        summary = "Kündigung durch den Leistungserbringer (KDLE)",
        description = "Legt den Auftrag KUE-LE an (Rolle LEISTUNGSERBRINGER) und sendet die KDLE. ERLM und ENTM folgen über " +
            "POST /orders/{id}/messages. 409 bei vergebener Nummer oder unzulässigem Meldungscode.",
    )
    fun terminate(@RequestBody body: TerminationRequest): OrderDetailDto {
        val order = commands.terminate(
            PartnerId(body.partnerId),
            Termination(body.externalOrderNumber, body.contractNumber, body.clientNumber, body.shutdownDate, body.reason, body.lineId),
            body.codes.map { it.code to it.text },
            body.attributes,
        )
        audit.record(currentActor(), "order.terminate", "order:${order.id!!.value}", "partner=${body.partnerId} number=${body.externalOrderNumber}")
        return OrderDetailDto.of(order, service)
    }

    @GetMapping("/pv-requests")
    @PreAuthorize(Requires.ORDERS_READ)
    @Operation(
        summary = "Providerwechsel-Anfragen (AKM-PV als abgebender EKP)",
        description = "Neueste zuerst. `open=true` (Standard): nur Anfragen, deren RUEM-PV noch aussteht. Blättern mit `cursor`.",
    )
    fun pvRequests(
        @Parameter(description = "nur Anfragen ohne RUEM-PV") @RequestParam(defaultValue = "true") open: Boolean,
        @RequestParam(required = false) partnerId: Long?,
        @Parameter(description = "Cursor: kleinste `order.id` der vorigen Seite") @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<PvRequestDto> = orders.search(
        OrderFilter(
            role = OrderRole.ABGEBENDER_EKP,
            partnerId = partnerId?.let(::PartnerId),
            handover = if (open) HandoverPhase.ANNOUNCED else null,
            beforeId = cursor,
            limit = limit,
        ),
    ).map { order ->
        val announcement = commands.providerChangeAnnouncement(order.id!!)
        PvRequestDto(
            OrderDto.of(order), order.state.handover == HandoverPhase.ANNOUNCED, announcement?.archiveId,
            announcement?.providerName, announcement?.plannedTransferDate, announcement?.replyDeadline, announcement?.lineId ?: order.data.lineId,
        )
    }

    @PostMapping("/pv-requests/{id}/reply", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(Requires.ORDERS_WRITE)
    @Operation(
        summary = "Providerwechsel-Anfrage beantworten (RUEM-PV)",
        description = "Zustimmung oder Ablehnung. Die übrigen Pflichtfelder der RUEM-PV kommen aus der AKM-PV. " +
            "409, wenn die RUEM-PV schon gesendet ist (`allowedActions`).",
    )
    fun reply(@PathVariable id: Long, @RequestBody body: PvReplyRequest): OrderDetailDto {
        val order = commands.replyToProviderChange(
            OrderId(id),
            ProviderChangeReply(body.consent, body.providerName, body.answerCode, body.answerText),
            body.codes.map { it.code to it.text },
        )
        audit.record(currentActor(), "order.message", "order:$id", "RUEM-PV consent=${body.consent} ${body.codes.joinToString(",") { it.code }}")
        return OrderDetailDto.of(order, service)
    }
}
