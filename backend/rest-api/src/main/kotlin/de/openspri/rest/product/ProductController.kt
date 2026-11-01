package de.openspri.rest.product

import de.openspri.auth.AuditLog
import de.openspri.domain.BusinessCase
import de.openspri.domain.order.OrderRole
import de.openspri.domain.partner.PartnerAdministration
import de.openspri.domain.partner.PartnerId
import de.openspri.domain.partner.Product
import de.openspri.domain.partner.ProductCatalog
import de.openspri.domain.partner.ProductChange
import de.openspri.domain.partner.ProductChangeMatrixParser
import de.openspri.domain.partner.changeFor
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import de.openspri.rest.order.enumValue
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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

/** Product catalog (Anlage 4 is not normative, so it is maintained here) and product change matrix (Anlage 6). */
@RestController
@RequestMapping("/api/v1", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Produkte", description = "Produktkatalog je Partner und Produktwechselmatrix (AUFGABE 5.9, Anlage 4 und 6)")
class ProductController(
    private val catalog: ProductCatalog,
    private val partners: PartnerAdministration,
    private val audit: AuditLog,
) {
    @Schema(description = "Ein Produkt des Katalogs")
    data class ProductDto(
        @field:Schema(accessMode = Schema.AccessMode.READ_ONLY) val id: Long? = null,
        @field:Schema(description = "leer = gilt für alle Partner") val partnerId: Long? = null,
        @field:Schema(
            description = "AUFTRAGGEBER: Produkt des Partners, das wir bestellen; LEISTUNGSERBRINGER: unser Produkt, das der Partner bestellt",
            requiredMode = REQUIRED,
        )
        val role: String,
        @field:Schema(description = "Produktbezeichner wie im Auftrag (`produkt/bezeichner`)", example = "FTTH 100", requiredMode = REQUIRED) val identifier: String,
        @field:Schema(example = "FTTH") val productGroup: String? = null,
        val description: String? = null,
        @field:Schema(description = "inaktive Produkte zählen bei der Prüfung eingehender Aufträge nicht") val active: Boolean = true,
    )

    @Schema(description = "Ein Wechsel der Produktwechselmatrix")
    data class ChangeDto(
        @field:Schema(description = "Bestand", example = "FTTH 25", requiredMode = REQUIRED) val from: String,
        @field:Schema(description = "Ziel", example = "FTTH 50", requiredMode = REQUIRED) val to: String,
        @field:Schema(description = "LAE oder AEN-LMAE", requiredMode = REQUIRED) val businessCase: String,
    )

    @Schema(description = "Die Produktwechselmatrix eines Partners oder die Vorgabe für alle")
    data class MatrixDto(
        @field:Schema(description = "leer = Vorgabe für alle Partner") val partnerId: Long?,
        @field:Schema(requiredMode = REQUIRED) val changes: List<ChangeDto>,
    )

    @Schema(description = "Ergebnis der Abfrage: mit welchem Geschäftsfall wechselt man von `from` nach `to`")
    data class LookupDto(
        @field:Schema(requiredMode = REQUIRED) val from: String,
        @field:Schema(requiredMode = REQUIRED) val to: String,
        @field:Schema(description = "LAE, AEN-LMAE oder leer: Wechsel nur mit NEU und KUE-AG") val businessCase: String?,
        @field:Schema(description = "PARTNER (eigene Matrix des Partners), DEFAULT (Vorgabe) oder NONE", requiredMode = REQUIRED) val source: String,
    )

    // --- products ---------------------------------------------------------------------------------

    @GetMapping("/products")
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Produkte", description = "Mit `partnerId` die Produkte dieses Partners einschließlich derer für alle Partner.")
    fun list(@RequestParam(required = false) partnerId: Long?, @RequestParam(required = false) role: String?): List<ProductDto> =
        catalog.list(partnerId?.let(::partner), role?.let { enumValue<OrderRole>(it, "role") }).map(::toDto)

    @GetMapping("/products/{id}")
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Produkt lesen")
    fun get(@PathVariable id: Long): ProductDto = toDto(catalog.get(id) ?: throw NoSuchElementException("Produkt $id existiert nicht"))

    @PostMapping("/products", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(
        summary = "Produkt anlegen",
        description = "Sobald ein aktives Produkt (Rolle LEISTUNGSERBRINGER) für einen Partner gilt, prüft open-spri dessen Aufträge gegen den Katalog.",
    )
    fun create(@RequestBody body: ProductDto): ProductDto = save(null, body)

    @PutMapping("/products/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Produkt ändern")
    fun update(@PathVariable id: Long, @RequestBody body: ProductDto): ProductDto = save(id, body)

    @DeleteMapping("/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Produkt löschen", description = "Bestehende Aufträge bleiben unverändert; zum Auslaufen lassen besser `active=false`.")
    fun delete(@PathVariable id: Long) {
        if (!catalog.delete(id)) throw NoSuchElementException("Produkt $id existiert nicht")
        audit.record(currentActor(), "product.delete", "product:$id", null)
    }

    private fun save(id: Long?, body: ProductDto): ProductDto {
        val identifier = body.identifier.trim()
        require(identifier.isNotEmpty() && identifier.length <= 100) { "identifier: 1 bis 100 Zeichen" }
        val saved = catalog.save(
            Product(id, body.partnerId?.let(::partner), enumValue(body.role, "role"), identifier, body.productGroup?.trim()?.ifEmpty { null }, body.description, body.active),
        )
        audit.record(currentActor(), "product.save", "product:${saved.id}", identifier)
        return toDto(saved)
    }

    // --- product change matrix --------------------------------------------------------------------

    @GetMapping("/product-change-matrix")
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Produktwechselmatrix", description = "Ohne `partnerId` die Vorgabe für alle Partner.")
    fun matrix(@RequestParam(required = false) partnerId: Long?): MatrixDto {
        val partner = partnerId?.let(::partner)
        return MatrixDto(partnerId, catalog.changes(partner).map(::toDto))
    }

    @PutMapping("/product-change-matrix", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(
        summary = "Produktwechselmatrix ersetzen",
        description = "Ersetzt die Matrix vollständig. Eine eigene Matrix eines Partners gilt anstelle der Vorgabe; eine leere Liste löscht sie.",
    )
    fun replace(@RequestParam(required = false) partnerId: Long?, @RequestBody changes: List<ChangeDto>): MatrixDto {
        val partner = partnerId?.let(::partner)
        store(partner, changes.map { ProductChange(partner, it.from.trim(), it.to.trim(), changeCase(it.businessCase)) })
        return matrix(partnerId)
    }

    @PostMapping("/product-change-matrix/import", consumes = ["text/csv", MediaType.TEXT_PLAIN_VALUE])
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(
        summary = "Produktwechselmatrix aus Anlage 6 importieren",
        description = "CSV-Export der Tabelle aus Anlage 6 (Kopfzeile mit Zielprodukten, je Bestandsprodukt eine Zeile mit LAE/AEN-LMAE). " +
            "`FTTx` im Ziel wird durch die Technologie des Bestandsprodukts ersetzt (SA-013). Ersetzt die Matrix vollständig.",
    )
    fun import(@RequestParam(required = false) partnerId: Long?, @RequestBody csv: String): MatrixDto {
        val partner = partnerId?.let(::partner)
        store(partner, ProductChangeMatrixParser.parse(csv).map { it.copy(partnerId = partner) })
        return matrix(partnerId)
    }

    @GetMapping("/product-change-matrix/lookup")
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Geschäftsfall für einen Produktwechsel", description = "Die eigene Matrix des Partners, sonst die Vorgabe.")
    fun lookup(@RequestParam(required = false) partnerId: Long?, @RequestParam from: String, @RequestParam to: String): LookupDto {
        val partner = partnerId?.let(::partner)
        val change = catalog.changeFor(partner, from, to)
        val source = when {
            change == null -> "NONE"
            change.partnerId != null -> "PARTNER"
            else -> "DEFAULT"
        }
        return LookupDto(from, to, change?.businessCase?.code, source)
    }

    private fun store(partner: PartnerId?, changes: List<ProductChange>) {
        val duplicates = changes.groupBy { it.from.lowercase() to it.to.lowercase() }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "doppelte Wechsel: ${duplicates.joinToString { "${it.first} → ${it.second}" }}" }
        catalog.replaceChanges(partner, changes)
        audit.record(currentActor(), "product-change-matrix.replace", "partner:${partner?.value ?: "default"}", "entries=${changes.size}")
    }

    private fun changeCase(code: String): BusinessCase {
        val case = BusinessCase.entries.firstOrNull { it.code == code.trim() }
        require(case == BusinessCase.LAE || case == BusinessCase.AEN_LMAE) { "businessCase: erlaubt sind LAE und AEN-LMAE (Anlage 6)" }
        return case
    }

    private fun partner(id: Long): PartnerId = partners.get(PartnerId(id))?.id ?: throw NoSuchElementException("Partner $id existiert nicht")

    private fun toDto(p: Product) = ProductDto(p.id, p.partnerId?.value, p.role.name, p.identifier, p.productGroup, p.description, p.active)
    private fun toDto(c: ProductChange) = ChangeDto(c.from, c.to, c.businessCase.code)
}
