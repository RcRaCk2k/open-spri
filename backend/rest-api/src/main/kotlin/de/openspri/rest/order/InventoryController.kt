package de.openspri.rest.order

import de.openspri.domain.order.InventoryContract
import de.openspri.domain.order.InventoryFilter
import de.openspri.domain.order.InventoryStore
import de.openspri.domain.order.OrderRole
import de.openspri.domain.partner.PartnerId
import de.openspri.rest.auth.Requires
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** Inventory (AUFGABE 5.6, 8.5): contracts built from the completed orders. */
@RestController
@RequestMapping("/api/v1/inventory", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Bestand", description = "Verträge aus abgeschlossenen Aufträgen, Grundlage für KUE-AG, AEN-LMAE, LAE, EST und den Providerwechsel")
class InventoryController(private val inventory: InventoryStore) {

    @Schema(description = "Ein Vertrag im Bestand")
    data class InventoryContractDto(
        @field:Schema(requiredMode = REQUIRED) val id: Long,
        @field:Schema(description = "unsere Rolle: AUFTRAGGEBER (wir nutzen die Leitung des Partners) oder LEISTUNGSERBRINGER (der Partner nutzt unsere)", requiredMode = REQUIRED)
        val role: String,
        @field:Schema(requiredMode = REQUIRED) val partnerId: Long,
        @field:Schema(requiredMode = REQUIRED) val contractNumber: String,
        @field:Schema(requiredMode = REQUIRED) val lineId: String,
        val clientNumber: String?,
        val serviceNumber: String?,
        @field:Schema(requiredMode = REQUIRED) val products: List<String>,
        @field:Schema(description = "Auftrag, mit dem der Vertrag entstand") val orderId: Long?,
        @field:Schema(requiredMode = REQUIRED) val startedAt: Instant,
        @field:Schema(description = "Ende (Kündigung, Providerwechsel); leer = aktiv") val endedAt: Instant?,
    )

    @GetMapping
    @PreAuthorize(Requires.ORDERS_READ)
    @Operation(summary = "Bestand", description = "Neueste zuerst; `active=false` zeigt auch beendete Verträge. Blättern mit `cursor`.")
    fun list(
        @RequestParam(required = false) role: String?,
        @RequestParam(required = false) partnerId: Long?,
        @RequestParam(required = false) lineId: String?,
        @Parameter(description = "nur aktive Verträge") @RequestParam(defaultValue = "true") active: Boolean,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<InventoryContractDto> = inventory.search(
        InventoryFilter(role?.let { enumValue<OrderRole>(it, "role") }, partnerId?.let(::PartnerId), lineId, active, cursor, limit),
    ).map(::toDto)

    @GetMapping("/{lineId}")
    @PreAuthorize(Requires.ORDERS_READ)
    @Operation(summary = "Verträge einer Leitung", description = "Alle Verträge zur LineID, auch beendete, neueste zuerst.")
    fun line(@PathVariable lineId: String): List<InventoryContractDto> {
        val contracts = inventory.search(InventoryFilter(lineId = lineId, activeOnly = false, limit = 500))
        if (contracts.isEmpty()) throw NoSuchElementException("Kein Vertrag zur LineID $lineId")
        return contracts.map(::toDto)
    }

    private fun toDto(c: InventoryContract) = InventoryContractDto(
        c.id!!, c.role.name, c.partnerId.value, c.contractNumber, c.lineId, c.clientNumber, c.serviceNumber, c.products, c.orderId?.value, c.startedAt, c.endedAt,
    )
}
