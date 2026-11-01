package de.openspri.domain.partner

import de.openspri.domain.BusinessCase
import de.openspri.domain.order.OrderData
import de.openspri.domain.order.OrderRole
import java.time.Instant
import java.time.LocalDate

/** Validity of an agreement; both ends inclusive, null = open. */
data class Validity(val from: LocalDate? = null, val to: LocalDate? = null) {
    init {
        require(from == null || to == null || !to.isBefore(from)) { "gültig bis liegt vor gültig ab" }
    }

    fun covers(date: LocalDate) = (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))
}

/**
 * Rahmenvertrag with a partner (AUFGABE 5.9): the Auftraggebernummer and Leistungsnummer under which orders are
 * placed. [role] is our role: AUFTRAGGEBER = we order from the partner, LEISTUNGSERBRINGER = the partner orders from us.
 */
data class FrameworkContract(
    val id: Long?,
    val partnerId: PartnerId,
    val role: OrderRole,
    val clientNumber: String,
    val serviceNumber: String? = null,
    val description: String? = null,
    val validity: Validity = Validity(),
)

/**
 * Vollmacht (AUFGABE 5.7, R-11): the Auftraggeber ([clientNumber]/[serviceNumber]) allows the Besteller
 * ([ordererClientNumber]/[ordererServiceNumber]) to order in its name. [partnerId] is the Besteller's
 * partner, who sends the orders. The signed document can be attached.
 */
data class PowerOfAttorney(
    val id: Long?,
    val partnerId: PartnerId,
    val ordererClientNumber: String,
    val ordererServiceNumber: String,
    val clientNumber: String,
    val serviceNumber: String,
    val description: String? = null,
    val validity: Validity = Validity(),
    val documentName: String? = null,
)

/** Projektvereinbarung (R-12): a Projektkenner may only be used if agreed with the partner. */
data class ProjectAgreement(
    val id: Long?,
    val partnerId: PartnerId,
    val projectId: String,
    val description: String? = null,
    val validity: Validity = Validity(),
)

/** An attached document (e.g. the signed power of attorney). */
class AgreementDocument(val name: String, val contentType: String, val content: ByteArray)

/** Agreements per partner (REST, M4). */
interface PartnerAgreements {
    fun contracts(partnerId: PartnerId): List<FrameworkContract>
    fun saveContract(contract: FrameworkContract): FrameworkContract
    fun deleteContract(partnerId: PartnerId, id: Long): Boolean

    fun powersOfAttorney(partnerId: PartnerId): List<PowerOfAttorney>
    fun savePowerOfAttorney(power: PowerOfAttorney): PowerOfAttorney
    fun deletePowerOfAttorney(partnerId: PartnerId, id: Long): Boolean
    fun document(partnerId: PartnerId, powerOfAttorneyId: Long): AgreementDocument?
    fun saveDocument(partnerId: PartnerId, powerOfAttorneyId: Long, document: AgreementDocument): Boolean

    fun projects(partnerId: PartnerId): List<ProjectAgreement>
    fun saveProject(project: ProjectAgreement): ProjectAgreement
    fun deleteProject(partnerId: PartnerId, id: Long): Boolean
}

/**
 * A product of the catalog (AUFGABE 5.9; Anlage 4 is not normative, so the identifiers are maintained here).
 * [partnerId] null = applies to all partners. [role] as in [FrameworkContract]: AUFTRAGGEBER = a product of
 * the partner we can order, LEISTUNGSERBRINGER = our product the partner can order.
 */
data class Product(
    val id: Long?,
    val partnerId: PartnerId?,
    val role: OrderRole,
    val identifier: String,
    val productGroup: String? = null,
    val description: String? = null,
    val active: Boolean = true,
)

/** One cell of the product change matrix (Anlage 6): changing [from] to [to] is done with [businessCase]. */
data class ProductChange(val partnerId: PartnerId?, val from: String, val to: String, val businessCase: BusinessCase)

interface ProductCatalog {
    fun list(partnerId: PartnerId?, role: OrderRole?): List<Product>
    fun get(id: Long): Product?
    fun save(product: Product): Product
    fun delete(id: Long): Boolean

    /** The matrix of [partnerId] (null = default for all partners). */
    fun changes(partnerId: PartnerId?): List<ProductChange>
    fun replaceChanges(partnerId: PartnerId?, changes: List<ProductChange>)
}

/**
 * Which business case changes [from] to [to] (Anlage 6). A partner's own matrix replaces the default as a
 * whole once it has entries; null = the change is not possible with LAE/AEN-LMAE (only NEU and KUE-AG).
 */
fun ProductCatalog.changeFor(partnerId: PartnerId?, from: String, to: String): ProductChange? {
    val matrix = partnerId?.let(::changes)?.takeIf { it.isNotEmpty() } ?: changes(null)
    return matrix.firstOrNull { it.from.equals(from.trim(), ignoreCase = true) && it.to.equals(to.trim(), ignoreCase = true) }
}

/**
 * Checks of an inbound order against the agreements (AUFGABE 5.5 "fachliche Vorprüfungen", R-11, R-12). They
 * return findings like [de.openspri.domain.order.OrderRules]; as Leistungserbringer they are the basis for an ABBM.
 * Rahmenvertrag and product are only checked once at least one entry is maintained for the partner, so that a
 * new installation does not report every order.
 */
class AgreementRules(private val agreements: PartnerAgreements, private val products: ProductCatalog) {

    /** An order the partner sent to us (we are Leistungserbringer). */
    fun checkInbound(partnerId: PartnerId, data: OrderData, today: LocalDate): List<String> = buildList {
        if (data.ordererClientNumber != null && data.ordererServiceNumber != null) {
            val valid = agreements.powersOfAttorney(partnerId).any {
                it.validity.covers(today) && it.ordererClientNumber == data.ordererClientNumber && it.ordererServiceNumber == data.ordererServiceNumber &&
                    it.clientNumber == data.clientNumber && it.serviceNumber == data.serviceNumber
            }
            if (!valid) {
                add("R-11: Keine gültige Vollmacht des Auftraggebers ${data.clientNumber}/${data.serviceNumber} für den Besteller ${data.ordererClientNumber}/${data.ordererServiceNumber}")
            }
        }
        addAll(checkProject(partnerId, data, today))

        val contracts = agreements.contracts(partnerId).filter { it.role == OrderRole.LEISTUNGSERBRINGER }
        if (contracts.isNotEmpty() && data.clientNumber != null) {
            val number = data.ordererClientNumber ?: data.clientNumber
            val service = data.ordererServiceNumber ?: data.serviceNumber
            val known = contracts.any { it.validity.covers(today) && it.clientNumber == number && (it.serviceNumber == null || it.serviceNumber == service) }
            if (!known) add("Rahmenvertrag: Auftraggebernummer $number / Leistungsnummer ${service ?: "-"} ist für diesen Partner nicht hinterlegt")
        }

        val catalog = products.list(partnerId, OrderRole.LEISTUNGSERBRINGER).filter { it.active }
        if (catalog.isNotEmpty()) {
            val identifiers = catalog.map { it.identifier.lowercase() }.toSet()
            data.products.filter { it.lowercase() !in identifiers && !it.equals("Konnektivität", ignoreCase = true) }
                .forEach { add("Produkt '$it' ist nicht im Produktkatalog") }
        }
    }

    /** R-12 for our own order and for an inbound one: a Projektkenner needs a valid Projektvereinbarung. */
    fun checkProject(partnerId: PartnerId, data: OrderData, today: LocalDate): List<String> {
        val projectId = data.projectId ?: return emptyList()
        val valid = agreements.projects(partnerId).any { it.projectId == projectId && it.validity.covers(today) }
        return if (valid) emptyList() else listOf("R-12: Projektkenner $projectId ohne gültige Projektvereinbarung mit dem Partner")
    }
}

/** Maintenance window announced to the partners (NF-08, specification 11.5). */
data class MaintenanceAnnouncement(
    val id: Long?,
    val start: Instant,
    val end: Instant,
    val subject: String,
    val text: String,
    val partnerIds: List<PartnerId>,
    val createdAt: Instant,
    val createdBy: String,
    /** recipients the mail was handed to; partners without contact address are listed in [warnings] */
    val recipients: Int = 0,
    val warnings: List<String> = emptyList(),
)

interface MaintenanceAnnouncements {
    fun save(announcement: MaintenanceAnnouncement): MaintenanceAnnouncement
    fun list(limit: Int): List<MaintenanceAnnouncement>
}
