package de.openspri.domain.order

import de.openspri.domain.BusinessCase
import de.openspri.domain.partner.PartnerId
import java.time.Instant

/**
 * A contract of the inventory (AUFGABE 5.6, 8.4 `inventory_contract`): a line that is provided, built from the
 * completed orders (ENTM). [role] is our role in the contract: AUFTRAGGEBER = we use the line of [partnerId],
 * LEISTUNGSERBRINGER = [partnerId] uses our line.
 */
data class InventoryContract(
    val id: Long?,
    val role: OrderRole,
    val partnerId: PartnerId,
    val contractNumber: String,
    val lineId: String,
    val clientNumber: String?,
    val serviceNumber: String?,
    val products: List<String>,
    /** the order that created the contract */
    val orderId: OrderId?,
    val startedAt: Instant,
    val endedAt: Instant? = null,
) {
    val active: Boolean get() = endedAt == null
}

data class InventoryFilter(
    val role: OrderRole? = null,
    val partnerId: PartnerId? = null,
    val lineId: String? = null,
    val activeOnly: Boolean = true,
    val beforeId: Long? = null,
    val limit: Int = 50,
)

interface InventoryStore {
    fun add(contract: InventoryContract): InventoryContract

    /** Ends the active contracts of [role] on [lineId] (and/or with [contractNumber]); returns how many. */
    fun end(role: OrderRole, lineId: String?, contractNumber: String?, at: Instant): Int

    fun active(role: OrderRole, lineId: String): InventoryContract?

    fun search(filter: InventoryFilter): List<InventoryContract>
}

/**
 * Keeps the inventory up to date when an order is completed (ENTM, specification 5.1.1): NEU and PV create a
 * contract and end the previous one on the same line, KUE-AG and KUE-LE end it. As abgebender EKP the completed
 * handover (ENTM-PV) ends our contract. Cancelled orders (Storno) change nothing.
 */
class InventoryBook(private val store: InventoryStore) {

    /** The holder of the active contract on [lineId] as seen by us as Leistungserbringer (abgebender EKP of a PV). */
    fun holder(lineId: String): HandoverParty? =
        store.active(OrderRole.LEISTUNGSERBRINGER, lineId)?.let { HandoverParty(it.partnerId, it.contractNumber, it.clientNumber) }

    fun onChange(before: Order?, after: Order, now: Instant) {
        val role = if (after.role == OrderRole.ABGEBENDER_EKP) OrderRole.AUFTRAGGEBER else after.role
        val data = after.data
        if (after.state.handoverOnly) {
            if (before?.state?.handover != HandoverPhase.CLOSED && after.state.handover == HandoverPhase.CLOSED) {
                store.end(OrderRole.AUFTRAGGEBER, data.lineId, data.contractNumber, now)
            }
            return
        }
        val closedNow = before?.state?.phase != Phase.CLOSED && after.state.phase == Phase.CLOSED && !after.state.cancelled
        if (!closedNow) return
        when (data.businessCase) {
            BusinessCase.NEU, BusinessCase.PV -> {
                val lineId = data.lineId ?: return
                val contract = data.contractNumber ?: return
                store.end(role, lineId, null, now)
                store.add(InventoryContract(null, role, after.partnerId, contract, lineId, data.clientNumber, data.serviceNumber, data.products, after.id, now))
            }
            BusinessCase.KUE_AG, BusinessCase.KUE_LE -> store.end(role, data.lineId, data.contractNumber, now)
            else -> Unit
        }
    }
}

/** The other provider of a provider change: the holder of the current contract on the line (abgebender EKP). */
data class HandoverParty(val partnerId: PartnerId, val contractNumber: String, val clientNumber: String?)
