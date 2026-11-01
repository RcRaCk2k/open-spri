package de.openspri.domain.order

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.message.MessageType
import de.openspri.domain.partner.PartnerId
import java.time.Instant
import java.time.LocalDate

@JvmInline
value class OrderId(val value: Long)

/** Our role in an order. */
enum class OrderRole {
    /** We ordered from the partner (we receive QEB, ABM, …). */
    AUFTRAGGEBER,
    /** The partner ordered from us (we send QEB, ABM, …). */
    LEISTUNGSERBRINGER,
    /** Provider change: a customer moves away from us; we receive AKM-PV and answer with RUEM-PV. */
    ABGEBENDER_EKP,
}

/**
 * Business content of an order, as far as the rules and the order overview need it. The complete order is
 * kept verbatim in the message archive.
 */
data class OrderData(
    val externalOrderNumber: String,
    val businessCase: BusinessCase,
    val changeIndicator: ChangeIndicator = ChangeIndicator.STANDARD,
    val clientNumber: String? = null,
    val serviceNumber: String? = null,
    /** Bestellung im Namen Dritter (4.2): Auftraggeber- und Leistungsnummer des Bestellers. */
    val ordererClientNumber: String? = null,
    val ordererServiceNumber: String? = null,
    val desiredDate: LocalDate? = null,
    /** terminNeu of a Terminverschiebung. */
    val newDate: LocalDate? = null,
    val contractNumber: String? = null,
    val lineId: String? = null,
    val preAgreementId: String? = null,
    val homeId: String? = null,
    val products: List<String> = emptyList(),
    val projectId: String? = null,
    val couplingId: String? = null,
    val bracketId: String? = null,
    val bracketSize: Int? = null,
    /** ONT serial number / source MAC for the automatic connection identification (5.1.4). */
    val ontSerial: String? = null,
    /** EST: the order is a complaint (Reklamation) of a completed fault repair (6.1.5). */
    val complaint: Boolean = false,
    /** Key of the technical infrastructure for R-10 (LineID, HomeID or normalized address). */
    val infrastructureKey: String? = null,
) {
    /** Konnektivitätsauftrag: product position "Konnektivität" (5.1.2, Anlage 4). */
    val connectivity: Boolean get() = products.any { it.equals("Konnektivität", ignoreCase = true) }
}

data class Order(
    val id: OrderId?,
    val partnerId: PartnerId,
    val role: OrderRole,
    val data: OrderData,
    val state: OrderState,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long = 0,
)

/** One entry in the history of an order: an order, a request or a message, with findings of the checks. */
data class OrderHistoryEntry(
    val orderId: OrderId?,
    val partnerId: PartnerId,
    val archiveId: Long?,
    val kind: Kind,
    val messageType: MessageType? = null,
    val changeIndicator: ChangeIndicator = ChangeIndicator.STANDARD,
    val codes: List<String> = emptyList(),
    val findings: List<String> = emptyList(),
    val at: Instant,
) {
    enum class Kind { ORDER, REQUEST, MESSAGE }
}

interface OrderRepository {
    fun find(partnerId: PartnerId, role: OrderRole, externalOrderNumber: String): Order?

    /** Orders of the given role with this external order number, of any partner (RUEM-PV comes from the EKPab). */
    fun findAnyPartner(role: OrderRole, externalOrderNumber: String, businessCase: BusinessCase): List<Order>

    /** R-10: is there an unfinished order for the same technical infrastructure? */
    fun hasOpenOrder(role: OrderRole, infrastructureKey: String, except: OrderId?): Boolean

    /** Inserts or updates (optimistic locking via [Order.version]). Returns the stored order. */
    fun save(order: Order): Order

    fun append(entry: OrderHistoryEntry)
}
