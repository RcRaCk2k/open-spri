package de.openspri.simulator

import de.openspri.domain.ChangeIndicator
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.OrderData
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.RequestKind
import tools.jackson.databind.JsonNode

/**
 * The other side of a scenario (AUFGABE 10.1 items 5 and 6): the [SimulatedPartner] (KFT-like, SOAP) or a second
 * open-spri instance ([InstanceCounterpart], self-test AG ↔ LE). The [ScenarioRunner] only uses this interface.
 */
interface Counterpart {
    val name: String

    /** What the counterpart received from our instance about this order number, oldest first. */
    fun received(externalOrderNumber: String): List<Received>

    /** Requests of ours about this order number the counterpart did not accept (negative TEQ, findings). */
    fun rejections(externalOrderNumber: String): List<String>

    /** Whether the counterpart can answer the next request with TEQ 0991 (TF S.8.2.10). */
    val canOverload: Boolean

    /**
     * Whether the counterpart can send an AKM-PV without a PV order (view of the abgebender EKP, Z.6.1.07 ff.). An
     * open-spri instance needs the PV order of a third participant (aufnehmender EKP) for that.
     */
    val canAnnounceProviderChange: Boolean

    /** Answer the next request with TEQ 0991 "überlastet". */
    fun overloadNext()

    /** True while [overloadNext] has not been used yet. */
    val overloadPending: Boolean

    /** Agreements the counterpart needs for our orders (Projektkenner, Vollmacht, Produkte); [partnerId] = we at the counterpart. */
    fun prepare() {}

    /** Sends an order; returns the TEQ (or OK once accepted) and the order for later changes. */
    fun sendOrder(auftrag: JsonNode): Pair<TeqCode?, ByteArray>

    /** Storno, Terminverschiebung or Reklamation of an order sent before. */
    fun sendChange(original: ByteArray, kind: RequestKind, newDate: java.time.LocalDate?, complaint: JsonNode? = null): TeqCode?

    /** A message about [data] (the order as the counterpart knows it) in the counterpart's [role]. */
    fun sendMessage(data: OrderData, role: OrderRole, type: MessageType, changeIndicator: ChangeIndicator, codes: List<String>, attributes: JsonNode?): TeqCode?
}
