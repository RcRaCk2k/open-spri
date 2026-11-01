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
import de.openspri.soap.NotFoundException
import de.openspri.soap.OrderCommands
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
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
    @PreAuthorize(Requires.ORDERS_WRITE)
    @Operation(
        summary = "Auftrag anlegen (Entwurf) oder sofort senden",
        description = "Prüft gegen XSD und Anlage 1. Ohne `send` entsteht ein Entwurf (201, Phase DRAFT), der mit " +
            "POST /orders/{id}/send gesendet wird. Mit `send=true` prüft open-spri zusätzlich die Auftragsregeln und legt den " +
            "Auftrag sofort in den Postausgang (202). Die Zustellung läuft asynchron (GET /deliveries?orderId=…). " +
            "400: Inhalt ungültig, 409: fachlich nicht zulässig.",
    )
    fun create(
        @RequestBody body: CreateOrderRequest,
        @Parameter(description = "true: sofort senden statt Entwurf") @RequestParam(defaultValue = "false") send: Boolean,
    ): ResponseEntity<OrderDetailDto> {
        if (!send) {
            val draft = commands.saveDraft(PartnerId(body.partnerId), body.auftrag)
            audit.record(currentActor(), "order.draft", "order:${draft.id!!.value}", "partner=${body.partnerId} number=${draft.data.externalOrderNumber}")
            return ResponseEntity.status(HttpStatus.CREATED).body(detail(draft.id!!))
        }
        val order = commands.submitOrder(PartnerId(body.partnerId), body.auftrag)
        audit.record(currentActor(), "order.submit", "order:${order.id!!.value}", "partner=${body.partnerId} number=${order.data.externalOrderNumber}")
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(detail(order.id!!))
    }

    @GetMapping("/{id}/draft")
    @PreAuthorize(Requires.ORDERS_READ)
    @Operation(summary = "Inhalt eines Entwurfs", description = "Das `auftrag`-JSON wie gespeichert. 404, wenn der Auftrag kein Entwurf ist.")
    fun draft(@PathVariable id: Long): CreateOrderRequest {
        val order = commands.order(OrderId(id))
        val content = commands.draft(OrderId(id)) ?: throw NotFoundException("Auftrag $id ist kein Entwurf")
        return CreateOrderRequest(order.partnerId.value, content)
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.ORDERS_WRITE)
    @Operation(summary = "Entwurf ändern", description = "Ersetzt den Inhalt. 409, wenn der Auftrag schon gesendet ist.")
    fun updateDraft(@PathVariable id: Long, @RequestBody body: CreateOrderRequest): OrderDetailDto {
        commands.saveDraft(PartnerId(body.partnerId), body.auftrag, existing = OrderId(id))
        audit.record(currentActor(), "order.draft", "order:$id", "update")
        return detail(OrderId(id))
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Requires.ORDERS_WRITE)
    @Operation(summary = "Entwurf löschen", description = "Nur Entwürfe; ein gesendeter Auftrag endet mit Storno oder durch den Partner (409).")
    fun deleteDraft(@PathVariable id: Long) {
        commands.deleteDraft(OrderId(id))
        audit.record(currentActor(), "order.draft.delete", "order:$id", null)
    }

    @PostMapping("/{id}/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(Requires.ORDERS_WRITE)
    @Operation(
        summary = "Entwurf senden",
        description = "Baut den Auftrag mit aktuellem Kopf neu, prüft XSD, Anlage 1 und Auftragsregeln und legt ihn in den Postausgang.",
    )
    fun send(@PathVariable id: Long): OrderDetailDto {
        val order = commands.sendDraft(OrderId(id))
        audit.record(currentActor(), "order.submit", "order:$id", "partner=${order.partnerId.value} number=${order.data.externalOrderNumber}")
        return detail(OrderId(id))
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(Requires.ORDERS_WRITE)
    @Operation(
        summary = "Storno senden",
        description = "Der Storno wird aus dem ursprünglichen Auftrag abgeleitet (5.1.3). 409 enthält in `allowedActions`, was jetzt zulässig ist.",
    )
    fun cancel(@PathVariable id: Long): OrderDetailDto = change(id, RequestKind.STORNO, null, null)

    @PostMapping("/{id}/reschedule", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(Requires.ORDERS_WRITE)
    @Operation(summary = "Terminverschiebung senden", description = "Der ursprüngliche Auftrag mit `terminNeu` (5.1.4).")
    fun reschedule(@PathVariable id: Long, @RequestBody body: RescheduleRequest): OrderDetailDto =
        change(id, RequestKind.TERMINVERSCHIEBUNG, body.newDate, null)

    @PostMapping("/{id}/complaint", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(Requires.ORDERS_WRITE)
    @Operation(summary = "Reklamation einer Entstörung senden", description = "Ein Entstörauftrag mit dem Element `reklamation` (6.1.5).")
    fun complaint(@PathVariable id: Long, @RequestBody body: ComplaintRequest): OrderDetailDto =
        change(id, RequestKind.REKLAMATION, null, body.auftrag)

    @GetMapping("/{id}/allowed-actions")
    @PreAuthorize(Requires.ORDERS_READ)
    @Operation(summary = "Zulässige Aktionen", description = "Was wir nach dem Zustandsautomaten jetzt senden dürfen.")
    fun allowedActions(@PathVariable id: Long): List<ActionDto> = service.allowedActions(commands.order(OrderId(id))).map(ActionDto::of)

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

    private fun change(id: Long, kind: RequestKind, newDate: java.time.LocalDate?, auftrag: tools.jackson.databind.JsonNode?): OrderDetailDto {
        commands.request(OrderId(id), kind, newDate, auftrag)
        audit.record(currentActor(), "order.request", "order:$id", kind.name)
        return detail(OrderId(id))
    }

    private fun detail(id: OrderId) = OrderDetailDto.of(commands.order(id), service)
}

internal inline fun <reified E : Enum<E>> enumValue(value: String, field: String): E =
    enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw IllegalArgumentException("$field: erlaubt sind ${enumValues<E>().joinToString { it.name }}")
