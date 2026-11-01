package de.openspri.rest.order

import de.openspri.auth.AuditLog
import de.openspri.auth.Scope
import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.OrderId
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.OutboundOrderService
import de.openspri.domain.order.Phase
import de.openspri.domain.order.RequestKind
import de.openspri.domain.partner.PartnerId
import de.openspri.domain.query.OrderFilter
import de.openspri.domain.query.OrderQueries
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import de.openspri.soap.OrderCommands
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/orders", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Aufträge", description = "Aufträge in allen Rollen, Änderungen und Meldungen (AUFGABE 5.5/5.6)")
class OrderController(
    private val orders: OrderQueries,
    private val commands: OrderCommands,
    private val service: OutboundOrderService,
    private val audit: AuditLog,
) {
    @GetMapping
    @PreAuthorize(Requires.ORDERS_READ)
    @Operation(summary = "Aufträge suchen", description = "Neueste zuerst. Blättern mit `cursor` = kleinste `id` der vorigen Seite.")
    fun list(
        @RequestParam(required = false) role: String?,
        @RequestParam(required = false) partnerId: Long?,
        @Parameter(description = "z. B. NEU, KUE-AG, EST") @RequestParam(required = false) businessCase: String?,
        @RequestParam(required = false) phase: String?,
        @Parameter(description = "Präfixsuche in externer Auftragsnummer, LineID und Vertragsnummer") @RequestParam(required = false) q: String?,
        @Parameter(description = "nur nicht abgeschlossene Aufträge") @RequestParam(defaultValue = "false") open: Boolean,
        @Parameter(description = "Cursor: kleinste `id` der vorigen Seite") @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<OrderDto> = orders.search(
        OrderFilter(
            role = role?.let { enumValue<OrderRole>(it, "role") },
            partnerId = partnerId?.let(::PartnerId),
            businessCase = businessCase?.let { BusinessCase.fromCode(it) },
            phase = phase?.let { enumValue<Phase>(it, "phase") },
            text = q,
            openOnly = open,
            beforeId = cursor,
            limit = limit,
        ),
    ).map(OrderDto::of)

    @GetMapping("/{id}")
    @PreAuthorize(Requires.ORDERS_READ)
    @Operation(summary = "Auftrag mit zulässigen Aktionen lesen")
    fun get(@PathVariable id: Long): OrderDetailDto = detail(OrderId(id))

    @GetMapping("/{id}/history")
    @PreAuthorize(Requires.ORDERS_READ)
    @Operation(summary = "Verlauf eines Auftrags", description = "Aufträge, Änderungen und Meldungen in beiden Richtungen, älteste zuerst.")
    fun history(@PathVariable id: Long): List<HistoryDto> {
        commands.order(OrderId(id))
        return orders.history(OrderId(id)).map(HistoryDto::of)
    }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(Requires.ORDERS_WRITE)
    @Operation(
        summary = "Auftrag an einen Partner senden",
        description = "Prüft gegen XSD, Anlage 1 und die Auftragsregeln und legt den Auftrag in den Postausgang. " +
            "Die Zustellung läuft asynchron (GET /deliveries?orderId=…). 400: Inhalt ungültig, 409: fachlich nicht zulässig.",
    )
    fun create(@RequestBody body: CreateOrderRequest): OrderDetailDto {
        val order = commands.submitOrder(PartnerId(body.partnerId), body.auftrag)
        audit.record(currentActor(), "order.submit", "order:${order.id!!.value}", "partner=${body.partnerId} number=${order.data.externalOrderNumber}")
        return detail(order.id!!)
    }

    @PostMapping("/{id}/requests", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(Requires.ORDERS_WRITE)
    @Operation(
        summary = "Storno, Terminverschiebung oder Reklamation senden",
        description = "Storno und Terminverschiebung werden aus dem ursprünglichen Auftrag abgeleitet (5.1.3/5.1.4). " +
            "409 enthält in `allowedActions`, was jetzt zulässig ist.",
    )
    fun request(@PathVariable id: Long, @RequestBody body: ChangeRequest): OrderDetailDto {
        val kind = enumValue<RequestKind>(body.kind, "kind")
        commands.request(OrderId(id), kind, body.newDate, body.auftrag)
        audit.record(currentActor(), "order.request", "order:$id", kind.name)
        return detail(OrderId(id))
    }

    @PostMapping("/{id}/messages", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("${Requires.ORDERS_WRITE} or ${Requires.LE_MESSAGES_WRITE}")
    @Operation(
        summary = "Meldung zu einem Auftrag senden",
        description = "Als Leistungserbringer (QEB, ABM, …, Scope `le:messages:write`), als Auftraggeber (ZWM-AG, TBK-AG, ERLM-K) " +
            "oder als abgebender EKP (RUEM-PV). 409 enthält in `allowedActions`, was jetzt zulässig ist.",
    )
    fun message(@PathVariable id: Long, @RequestBody body: SendMessageRequest): OrderDetailDto {
        val order = commands.order(OrderId(id))
        val required = if (order.role == OrderRole.LEISTUNGSERBRINGER) Scope.LE_MESSAGES_WRITE else Scope.ORDERS_WRITE
        if (required !in currentActor().scopes) throw AccessDeniedException("Scope ${required.value} fehlt")
        val type = MessageType.fromCode(body.type) ?: throw IllegalArgumentException("Unbekannter Meldungstyp ${body.type}")
        val indicator = ChangeIndicator.entries.firstOrNull { it.code == body.changeIndicator }
            ?: throw IllegalArgumentException("changeIndicator: erlaubt sind ${ChangeIndicator.entries.joinToString { it.code }}")
        commands.message(OrderId(id), type, indicator, body.codes.map { it.code to it.text }, body.attributes)
        audit.record(currentActor(), "order.message", "order:$id", "${type.code} ${body.codes.joinToString(",") { it.code }}")
        return detail(OrderId(id))
    }

    private fun detail(id: OrderId): OrderDetailDto {
        val order = commands.order(id)
        return OrderDetailDto(OrderDto.of(order), service.allowedActions(order).map(ActionDto::of))
    }
}

internal inline fun <reified E : Enum<E>> enumValue(value: String, field: String): E =
    enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw IllegalArgumentException("$field: erlaubt sind ${enumValues<E>().joinToString { it.name }}")
