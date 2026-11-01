package de.openspri.domain.order

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.message.MessageType
import de.openspri.domain.message.MessageType.ABBM
import de.openspri.domain.message.MessageType.ABBM_PV
import de.openspri.domain.message.MessageType.ABM
import de.openspri.domain.message.MessageType.ABM_PV
import de.openspri.domain.message.MessageType.AKM_PV
import de.openspri.domain.message.MessageType.ENTM
import de.openspri.domain.message.MessageType.ENTM_PV
import de.openspri.domain.message.MessageType.ERLM
import de.openspri.domain.message.MessageType.ERLM_K
import de.openspri.domain.message.MessageType.ERLM_PV
import de.openspri.domain.message.MessageType.KDLE
import de.openspri.domain.message.MessageType.MTAM
import de.openspri.domain.message.MessageType.QEB
import de.openspri.domain.message.MessageType.RUEM_PV
import de.openspri.domain.message.MessageType.TAM
import de.openspri.domain.message.MessageType.TBK_AG
import de.openspri.domain.message.MessageType.TBK_LE
import de.openspri.domain.message.MessageType.VZM
import de.openspri.domain.message.MessageType.ZWM_AG
import de.openspri.domain.message.MessageType.ZWM_LE

/**
 * Phase of the main exchange between Auftraggeber and Leistungserbringer (specification chapter 5-8).
 * The same lifecycle is used in both roles: as AG we follow the partner's messages, as LE we check our own
 * messages before sending them.
 */
enum class Phase(val terminal: Boolean = false) {
    /** KUE-LE only: the order is created by the Leistungserbringer with KDLE. */
    NOT_STARTED,
    /** Order sent / received (positive TEQ), no business answer yet. */
    SUBMITTED,
    /** QEB: order complete and plausible. */
    ACKNOWLEDGED,
    /** ABM: binding date confirmed (or KDLE announced for KUE-LE). */
    CONFIRMED,
    /** TAM/MTAM: a new appointment with the end customer is required (NEU, LAE, PV). */
    APPOINTMENT_REQUESTED,
    /** ERLM: technically completed. */
    COMPLETED,
    /** ENTM: billing confirmed. */
    CLOSED(terminal = true),
    /** ABBM: order cannot be executed. */
    ABORTED(terminal = true),
}

/** Requests of the Auftraggeber on an existing order (same external order number). */
enum class RequestKind(val changeIndicator: ChangeIndicator) {
    STORNO(ChangeIndicator.STORNO),
    TERMINVERSCHIEBUNG(ChangeIndicator.TERMINVERSCHIEBUNG),
    REKLAMATION(ChangeIndicator.REKLAMATION),
}

/** Exchange with the abgebender Endkundenprovider during a provider change (PV, chapter 5.5). */
enum class HandoverPhase(val terminal: Boolean = false) {
    ANNOUNCED, // AKM-PV
    REPLIED, // RUEM-PV
    CONFIRMED, // ABM-PV
    COMPLETED, // ERLM-PV
    CLOSED(terminal = true), // ENTM-PV
    ABORTED(terminal = true), // ABBM-PV
}

/** Something that happens to an order. */
sealed interface OrderEvent {
    /** A business message. [changeIndicator] is the `aenderungsKennzeichen` of the order it answers. */
    data class Message(
        val type: MessageType,
        val changeIndicator: ChangeIndicator = ChangeIndicator.STANDARD,
        val codes: List<String> = emptyList(),
    ) : OrderEvent

    /** The Auftraggeber sends a Storno, Terminverschiebung or Reklamation for this order. */
    data class Request(val kind: RequestKind) : OrderEvent
}

/**
 * Lifecycle state of one order.
 *
 * @property connectivity Konnektivitätsauftrag (5.1.2): Terminverschiebung only after ABM
 * @property bracketed order with Auftragsklammer (4.3.3): Terminverschiebung only after ABM
 * @property openRequests requests waiting for an answer; at most one per kind (R-03)
 * @property cancelled a Storno was accepted (ERLM 0012); the order ends with ENTM without own completion (R-04)
 * @property handover exchange with the abgebender EKP; null if not (yet) started or not a PV order
 * @property handoverOnly this record is the abgebender EKP's view of a provider change (no main exchange)
 */
data class OrderState(
    val businessCase: BusinessCase,
    val phase: Phase,
    val connectivity: Boolean = false,
    val bracketed: Boolean = false,
    val openRequests: Set<RequestKind> = emptySet(),
    val cancelled: Boolean = false,
    val complaints: Int = 0,
    val appointmentRequested: Boolean = false,
    val handover: HandoverPhase? = null,
    val handoverOnly: Boolean = false,
) {
    val finished: Boolean get() = if (handoverOnly) handover?.terminal == true else phase.terminal
}

sealed interface Transition {
    data class Accepted(val state: OrderState) : Transition
    data class Rejected(val reason: String) : Transition
}

/**
 * State machine for all business cases of S/PRI V4.4. Sources: specification chapter 5/6 (process),
 * 7.2 (messages per business case), 4.3.3 (Auftragsklammer), Anlage 7 (EPKs) and the KFT test case list.
 * Each rule carries its reference (R-xx = docs/AUFGABE-SPRI.md chapter 5.2).
 */
object OrderLifecycle {

    /** Messages allowed per business case (specification 7.2), without the PV handover messages. */
    private val ALLOWED: Map<BusinessCase, Set<MessageType>> = run {
        val base = setOf(QEB, ABM, ERLM, ENTM, ABBM, VZM)
        val appointment = setOf(TAM, MTAM, ERLM_K)
        mapOf(
            BusinessCase.NEU to base + appointment + ZWM_LE,
            BusinessCase.KUE_AG to base,
            BusinessCase.AEN_LMAE to base,
            BusinessCase.LAE to base + appointment,
            BusinessCase.PV to base + appointment,
            BusinessCase.KUE_LE to setOf(KDLE, ERLM, ENTM),
            BusinessCase.EST to setOf(QEB, ERLM, ENTM, ABBM, VZM, TAM, ZWM_LE, ZWM_AG, TBK_LE, TBK_AG),
            BusinessCase.DIAG_GET to setOf(ERLM, ABBM),
            BusinessCase.DIAG_SET to setOf(ERLM, ABBM),
        )
    }

    private val HANDOVER_MESSAGES = setOf(AKM_PV, RUEM_PV, ABM_PV, ERLM_PV, ENTM_PV, ABBM_PV)

    /** A new order sent by the Auftraggeber and acknowledged with positive TEQ. */
    fun submitted(businessCase: BusinessCase, connectivity: Boolean = false, bracketed: Boolean = false): OrderState {
        require(businessCase != BusinessCase.KUE_LE) { "KUE-LE is started by the Leistungserbringer with KDLE" }
        return OrderState(businessCase, Phase.SUBMITTED, connectivity = connectivity, bracketed = bracketed)
    }

    /** Kündigung durch Leistungserbringer: the order record is created by the KDLE message (5.2.2). */
    fun announcedByLeistungserbringer(): OrderState = OrderState(BusinessCase.KUE_LE, Phase.NOT_STARTED)

    /** View of the abgebender EKP: the record is created by AKM-PV (5.5.1). */
    fun handoverAnnounced(): OrderState =
        OrderState(BusinessCase.PV, Phase.NOT_STARTED, handoverOnly = true)

    fun apply(state: OrderState, event: OrderEvent): Transition = when (event) {
        is OrderEvent.Request -> request(state, event.kind)
        is OrderEvent.Message ->
            if (event.type in HANDOVER_MESSAGES) handover(state, event.type)
            else if (state.handoverOnly) reject("${event.type.code} gehört nicht zum Austausch mit dem abgebenden EKP")
            else message(state, event)
    }

    /** All transitions that are possible in [state]; used for "allowed actions" in the API. */
    fun allowedEvents(state: OrderState): List<OrderEvent> {
        val candidates = MessageType.entries.flatMap { type ->
            ChangeIndicator.entries.map { OrderEvent.Message(type, it) }
        } + RequestKind.entries.map { OrderEvent.Request(it) }
        return candidates.filter { apply(state, it) is Transition.Accepted }
    }

    // --- requests of the Auftraggeber -------------------------------------------------------------

    private fun request(state: OrderState, kind: RequestKind): Transition {
        if (state.handoverOnly) return reject("Der abgebende EKP stellt keine Aufträge zu einem Providerwechsel")
        if (kind.changeIndicator !in state.businessCase.changeIndicators) {
            return reject("${kind.changeIndicator.code} ist für ${state.businessCase.code} nicht vorgesehen (4.1)")
        }
        if (kind in state.openRequests) {
            return reject("Es ist bereits ein Auftrag ${kind.changeIndicator.code} offen (R-03)")
        }
        val phase = state.phase
        return when (kind) {
            RequestKind.STORNO ->
                if (phase in setOf(Phase.SUBMITTED, Phase.ACKNOWLEDGED, Phase.CONFIRMED, Phase.APPOINTMENT_REQUESTED) && !state.cancelled) {
                    accept(state.copy(openRequests = state.openRequests + kind))
                } else {
                    reject("Storno ist in Phase $phase nicht mehr möglich (5.1.3)")
                }
            RequestKind.TERMINVERSCHIEBUNG -> {
                val afterAbm = phase == Phase.CONFIRMED || phase == Phase.APPOINTMENT_REQUESTED
                when {
                    (state.connectivity || state.bracketed) && !afterAbm ->
                        reject("Terminverschiebung ist bei Konnektivitätsauftrag und Auftragsklammer erst nach ABM zulässig (5.1.4, 4.3.3)")
                    phase == Phase.ACKNOWLEDGED || afterAbm -> accept(state.copy(openRequests = state.openRequests + kind))
                    else -> reject("Terminverschiebung ist in Phase $phase nicht möglich (5.1.4)")
                }
            }
            RequestKind.REKLAMATION ->
                if (phase == Phase.CLOSED && !state.cancelled) {
                    // 6.1.5: the complaint reopens the fault repair
                    accept(state.copy(phase = Phase.SUBMITTED, complaints = state.complaints + 1, appointmentRequested = false))
                } else {
                    reject("Reklamation ist erst nach Abschluss der Entstörung möglich (6.1.5)")
                }
        }
    }

    // --- messages of the main exchange ------------------------------------------------------------

    private fun message(state: OrderState, event: OrderEvent.Message): Transition {
        val type = event.type
        if (type !in ALLOWED.getValue(state.businessCase)) {
            return reject("${type.code} ist für ${state.businessCase.code} nicht vorgesehen (7.2)")
        }
        return when (event.changeIndicator) {
            ChangeIndicator.STANDARD, ChangeIndicator.REKLAMATION -> standard(state, type)
            ChangeIndicator.STORNO -> answerToStorno(state, type)
            ChangeIndicator.TERMINVERSCHIEBUNG -> answerToReschedule(state, type)
        }
    }

    private fun standard(state: OrderState, type: MessageType): Transition {
        val phase = state.phase
        val active = phase in setOf(Phase.SUBMITTED, Phase.ACKNOWLEDGED, Phase.CONFIRMED, Phase.APPOINTMENT_REQUESTED)
        if (state.cancelled && type != ENTM) return reject("Der Auftrag wurde storniert, erwartet wird nur noch ENTM (R-04)")
        val bc = state.businessCase
        return when (type) {
            KDLE -> if (phase == Phase.NOT_STARTED) accept(state.copy(phase = Phase.CONFIRMED)) else reject("KDLE eröffnet die Kündigung durch den Leistungserbringer (5.2.2)")
            QEB -> when (phase) {
                Phase.SUBMITTED -> accept(state.copy(phase = Phase.ACKNOWLEDGED))
                Phase.ACKNOWLEDGED -> accept(state) // repeated QEB, e.g. after a negative TEQ (TF S.1.2.10)
                else -> reject("QEB ist nur als erste Antwort auf den Auftrag zulässig")
            }
            VZM -> if (active) accept(state) else reject("VZM ist nach Abschluss des Auftrags nicht zulässig")
            ZWM_LE, ZWM_AG, TBK_LE -> if (phase in setOf(Phase.ACKNOWLEDGED, Phase.CONFIRMED, Phase.APPOINTMENT_REQUESTED)) accept(state)
                else reject("${type.code} ist erst nach QEB und vor ERLM zulässig")
            TBK_AG -> if (bc == BusinessCase.EST && phase == Phase.ACKNOWLEDGED) accept(state.copy(appointmentRequested = false))
                else reject("TBK-AG ist nur während der Entstörung zulässig (6.1.4)")
            ABM -> when (phase) {
                Phase.ACKNOWLEDGED, Phase.CONFIRMED, Phase.APPOINTMENT_REQUESTED -> accept(state.copy(phase = Phase.CONFIRMED)) // repeated ABM: R-06
                else -> reject("ABM ist erst nach QEB und vor ERLM zulässig")
            }
            TAM -> when {
                bc == BusinessCase.EST && phase == Phase.ACKNOWLEDGED -> accept(state.copy(appointmentRequested = true))
                phase == Phase.CONFIRMED || phase == Phase.APPOINTMENT_REQUESTED -> accept(state.copy(phase = Phase.APPOINTMENT_REQUESTED))
                else -> reject("TAM ist erst nach ABM zulässig (R-08)")
            }
            MTAM -> if (phase == Phase.APPOINTMENT_REQUESTED) accept(state) else reject("MTAM erinnert an eine offene TAM (R-08)")
            ERLM_K -> if (phase == Phase.CONFIRMED || phase == Phase.APPOINTMENT_REQUESTED) accept(state)
                else reject("ERLM-K ist nur nach ABM und vor ERLM zulässig (R-09)")
            ERLM -> when {
                bc == BusinessCase.DIAG_GET || bc == BusinessCase.DIAG_SET ->
                    if (phase == Phase.SUBMITTED) accept(state.copy(phase = Phase.CLOSED)) else reject("Diagnose ist bereits beendet")
                bc == BusinessCase.EST && phase == Phase.ACKNOWLEDGED -> accept(state.copy(phase = Phase.COMPLETED, appointmentRequested = false))
                phase == Phase.CONFIRMED || phase == Phase.APPOINTMENT_REQUESTED -> accept(state.copy(phase = Phase.COMPLETED))
                else -> reject("ERLM ist in Phase $phase nicht zulässig")
            }
            ENTM -> if (phase == Phase.COMPLETED) accept(state.copy(phase = Phase.CLOSED)) else reject("ENTM folgt auf ERLM")
            ABBM -> when {
                active && !(bc == BusinessCase.KUE_LE) -> accept(state.copy(phase = Phase.ABORTED, openRequests = emptySet()))
                else -> reject("ABBM ist nach Abschluss des Auftrags nicht zulässig")
            }
            else -> reject("${type.code} gehört nicht zum Hauptaustausch")
        }
    }

    /** Answers to a Storno (5.1.3): ERLM 0012 + ENTM on success, ABBM if the Storno is not possible. */
    private fun answerToStorno(state: OrderState, type: MessageType): Transition {
        if (RequestKind.STORNO !in state.openRequests && !(state.cancelled && type == ENTM)) {
            return reject("${type.code} zu einem Storno, obwohl kein Storno offen ist")
        }
        return when (type) {
            VZM -> accept(state)
            ERLM -> accept(state.copy(phase = Phase.COMPLETED, cancelled = true, openRequests = emptySet()))
            ENTM -> if (state.cancelled && state.phase == Phase.COMPLETED) accept(state.copy(phase = Phase.CLOSED))
                else reject("ENTM zum Storno folgt auf die ERLM des Stornos")
            ABBM -> accept(state.copy(openRequests = state.openRequests - RequestKind.STORNO)) // Storno rejected, order continues
            else -> reject("${type.code} ist keine Antwort auf ein Storno")
        }
    }

    /** Answers to a Terminverschiebung (5.1.4): ABM with the new date, or VZM/ABBM if it is not possible. */
    private fun answerToReschedule(state: OrderState, type: MessageType): Transition {
        if (RequestKind.TERMINVERSCHIEBUNG !in state.openRequests) {
            return reject("${type.code} zu einer Terminverschiebung, obwohl keine offen ist")
        }
        val closed = state.openRequests - RequestKind.TERMINVERSCHIEBUNG
        return when (type) {
            VZM -> accept(state)
            ABM -> accept(state.copy(phase = Phase.CONFIRMED, openRequests = closed))
            ABBM -> accept(state.copy(openRequests = closed)) // rejected, the previous date stays valid
            else -> reject("${type.code} ist keine Antwort auf eine Terminverschiebung")
        }
    }

    // --- provider change: exchange with the abgebender EKP (5.5.1) --------------------------------

    private fun handover(state: OrderState, type: MessageType): Transition {
        if (state.businessCase != BusinessCase.PV) return reject("${type.code} gibt es nur beim Providerwechsel")
        val current = state.handover
        fun to(next: HandoverPhase) = accept(state.copy(handover = next))
        return when (type) {
            AKM_PV -> when {
                current != null -> reject("AKM-PV wurde bereits versendet")
                state.handoverOnly || state.phase == Phase.ACKNOWLEDGED -> to(HandoverPhase.ANNOUNCED)
                else -> reject("AKM-PV folgt auf die QEB an den aufnehmenden EKP")
            }
            RUEM_PV -> if (current == HandoverPhase.ANNOUNCED) to(HandoverPhase.REPLIED) else reject("RUEM-PV antwortet auf AKM-PV")
            ABM_PV -> if (current == HandoverPhase.REPLIED || current == HandoverPhase.CONFIRMED) to(HandoverPhase.CONFIRMED)
                else reject("ABM-PV folgt auf RUEM-PV")
            ERLM_PV -> if (current == HandoverPhase.CONFIRMED) to(HandoverPhase.COMPLETED) else reject("ERLM-PV folgt auf ABM-PV")
            ENTM_PV -> if (current == HandoverPhase.COMPLETED) to(HandoverPhase.CLOSED) else reject("ENTM-PV folgt auf ERLM-PV")
            ABBM_PV -> if (current != null && !current.terminal && current != HandoverPhase.COMPLETED) to(HandoverPhase.ABORTED)
                else reject("ABBM-PV ist nur vor ERLM-PV zulässig")
            else -> reject("${type.code} gehört nicht zum Austausch mit dem abgebenden EKP")
        }
    }

    private fun accept(state: OrderState) = Transition.Accepted(state)
    private fun reject(reason: String) = Transition.Rejected(reason)
}
