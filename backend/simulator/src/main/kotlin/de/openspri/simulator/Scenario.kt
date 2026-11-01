package de.openspri.simulator

import de.openspri.domain.ChangeIndicator
import de.openspri.domain.message.MessageType
import de.openspri.domain.message.Party
import de.openspri.domain.order.OrderEvent
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.RequestKind

/** Who acts in a step: our instance (driven through its REST API) or the simulator (over SOAP). */
enum class Side { US, SIMULATOR }

sealed interface Action {
    /** The Auftraggeber places the order of the test case. */
    data object PlaceOrder : Action

    /** Storno, Terminverschiebung or Reklamation of the Auftraggeber. */
    data class Request(val kind: RequestKind) : Action

    /**
     * A message; [codes] empty = any code the message code table allows (`*2` in the test case list).
     * [newDate]: an ABM with a date later than the desired date ("->neuer Termin").
     * [overloaded]: the simulator answers it with TEQ 0991; [repeated]: our outbox sends it again by itself (TF S.8.2.10).
     */
    data class Message(
        val type: MessageType,
        val changeIndicator: ChangeIndicator,
        val codes: List<String>,
        val newDate: Boolean = false,
        val overloaded: Boolean = false,
        val repeated: Boolean = false,
    ) : Action
}

/** [thirdParty]: exchanged with the other provider of a provider change (`<…>` in the test case list). */
data class ScenarioStep(val side: Side, val action: Action, val thirdParty: Boolean = false)

/** A test case as a sequence of steps, seen from our [role]. */
data class Scenario(val case: KftTestCase, val role: OrderRole, val steps: List<ScenarioStep>)

/**
 * Turns the expected message sequence of a test case into steps (AUFGABE 10.1 item 5):
 * - Blatt "Partner": we are Auftraggeber, the simulator is Leistungserbringer. Test cases that start with
 *   AKM-PV (Z.6.1.07 ff.) are the view of the abgebender EKP. Messages in `<…>` are exchanged between the
 *   Leistungserbringer and the other provider and are not visible to us; they are skipped.
 * - Blatt "Supplier": we are Leistungserbringer, the simulator is Auftraggeber and, in `<…>`, abgebender EKP.
 * The sender of each message follows from its type ([MessageType.sender]); requests come from the Auftraggeber.
 */
object ScenarioPlanner {

    fun plan(case: KftTestCase): Scenario {
        val parsed = KftSequenceParser.parse(case.sequence)
        require(parsed.unparsed.isEmpty()) { "${case.id}: nicht lesbar: ${parsed.unparsed}" }
        val first = (parsed.events.firstOrNull() as? OrderEvent.Message)?.type
        val role = when {
            !case.partnerSheet -> OrderRole.LEISTUNGSERBRINGER
            first == MessageType.AKM_PV -> OrderRole.ABGEBENDER_EKP
            else -> OrderRole.AUFTRAGGEBER
        }
        val ourParty = when (role) {
            OrderRole.AUFTRAGGEBER -> Party.AUFTRAGGEBER
            OrderRole.LEISTUNGSERBRINGER -> Party.LEISTUNGSERBRINGER
            OrderRole.ABGEBENDER_EKP -> Party.ABGEBENDER_EKP
        }
        val variants = TestCaseVariants.of(case)
        val steps = mutableListOf<ScenarioStep>()
        // KUE-LE starts with the KDLE of the Leistungserbringer, the EKPab view with AKM-PV: no order
        if (first != MessageType.KDLE && first != MessageType.AKM_PV) {
            steps += ScenarioStep(if (role == OrderRole.AUFTRAGGEBER) Side.US else Side.SIMULATOR, Action.PlaceOrder)
        }
        parsed.steps.forEach { step ->
            if (step.thirdParty && role != OrderRole.LEISTUNGSERBRINGER) return@forEach
            steps += when (val event = step.event) {
                is OrderEvent.Request -> ScenarioStep(if (role == OrderRole.AUFTRAGGEBER) Side.US else Side.SIMULATOR, Action.Request(event.kind))
                is OrderEvent.Message -> {
                    val side = if (event.type.sender == ourParty) Side.US else Side.SIMULATOR
                    val newDate = event.type == MessageType.ABM && Variant.NEW_DATE in variants
                    // "ABM (0002, 0003 oder 0005->neuer Termin, *2)", TF S.1.1.02 "2x MC": 0002 plus one of 0003/0005
                    val codes = if (newDate && "oder" in case.sequence && event.codes.size == 3) event.codes.take(2) else event.codes
                    // TF S.8.2.10: our first QEB is answered with TEQ 0991, the second one is the automatic repetition
                    val overloadedSeen = steps.any { (it.action as? Action.Message)?.overloaded == true }
                    val overloaded = Variant.OVERLOADED in variants && side == Side.US && event.type == MessageType.QEB && !overloadedSeen
                    val repeated = Variant.OVERLOADED in variants && side == Side.US && event.type == MessageType.QEB && overloadedSeen
                    ScenarioStep(side, Action.Message(event.type, event.changeIndicator, codes, newDate, overloaded, repeated), step.thirdParty)
                }
                OrderEvent.Send -> error("not part of the test case list")
            }
        }
        return Scenario(case, role, steps)
    }
}
