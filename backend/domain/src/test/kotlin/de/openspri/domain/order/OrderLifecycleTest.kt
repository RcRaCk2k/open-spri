package de.openspri.domain.order

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.message.MessageType
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Rules that the replay of the KFT sequences cannot show: what must be rejected. */
class OrderLifecycleTest {

    private fun OrderState.then(vararg events: OrderEvent): OrderState = events.fold(this) { state, event ->
        when (val t = OrderLifecycle.apply(state, event)) {
            is Transition.Accepted -> t.state
            is Transition.Rejected -> throw AssertionError("$event rejected: ${t.reason}")
        }
    }

    private fun OrderState.rejects(event: OrderEvent): String =
        assertIs<Transition.Rejected>(OrderLifecycle.apply(this, event), "$event must be rejected in $this").reason

    private fun m(type: MessageType, indicator: ChangeIndicator = ChangeIndicator.STANDARD) = OrderEvent.Message(type, indicator)
    private val storno = OrderEvent.Request(RequestKind.STORNO)
    private val reschedule = OrderEvent.Request(RequestKind.TERMINVERSCHIEBUNG)

    private val neu get() = OrderLifecycle.submitted(BusinessCase.NEU)

    @Test
    fun `messages out of order are rejected`() {
        neu.rejects(m(MessageType.ABM))
        neu.rejects(m(MessageType.ENTM))
        neu.then(m(MessageType.QEB)).rejects(m(MessageType.TAM))
        neu.then(m(MessageType.QEB), m(MessageType.ABM), m(MessageType.ERLM)).rejects(m(MessageType.ABBM))
    }

    @Test
    fun `messages that do not exist for the business case are rejected (7_2)`() {
        assertContains(OrderLifecycle.submitted(BusinessCase.KUE_AG).then(m(MessageType.QEB), m(MessageType.ABM)).rejects(m(MessageType.TAM)), "7.2")
        OrderLifecycle.submitted(BusinessCase.DIAG_GET).rejects(m(MessageType.QEB))
        OrderLifecycle.submitted(BusinessCase.NEU).rejects(m(MessageType.AKM_PV))
    }

    @Test
    fun `only one open Storno and one open Terminverschiebung (R-03)`() {
        val confirmed = neu.then(m(MessageType.QEB), m(MessageType.ABM))
        assertContains(confirmed.then(storno).rejects(storno), "R-03")
        assertContains(confirmed.then(reschedule).rejects(reschedule), "R-03")
    }

    @Test
    fun `change indicators the business case does not have are rejected (4_1)`() {
        OrderLifecycle.submitted(BusinessCase.AEN_LMAE).then(m(MessageType.QEB)).rejects(reschedule)
        OrderLifecycle.submitted(BusinessCase.DIAG_SET).rejects(storno)
    }

    @Test
    fun `Terminverschiebung of a Konnektivitätsauftrag only after ABM (5_1_4)`() {
        val connectivity = OrderLifecycle.submitted(BusinessCase.NEU, connectivity = true).then(m(MessageType.QEB))
        assertContains(connectivity.rejects(reschedule), "erst nach ABM")
        connectivity.then(m(MessageType.ABM), reschedule)
    }

    @Test
    fun `answers to a change request require an open request`() {
        neu.then(m(MessageType.QEB)).rejects(m(MessageType.ERLM, ChangeIndicator.STORNO))
        neu.then(m(MessageType.QEB)).rejects(m(MessageType.ABM, ChangeIndicator.TERMINVERSCHIEBUNG))
    }

    @Test
    fun `accepted Storno ends the order without own completion (R-04)`() {
        val cancelled = neu.then(m(MessageType.QEB), storno, m(MessageType.ERLM, ChangeIndicator.STORNO))
        assertTrue(cancelled.cancelled)
        cancelled.rejects(m(MessageType.ABM))
        assertEquals(Phase.CLOSED, cancelled.then(m(MessageType.ENTM, ChangeIndicator.STORNO)).phase)
    }

    @Test
    fun `rejected Storno keeps the order running`() {
        val state = neu.then(m(MessageType.QEB), m(MessageType.ABM), storno, m(MessageType.ABBM, ChangeIndicator.STORNO))
        assertEquals(Phase.CONFIRMED, state.phase)
        assertTrue(state.openRequests.isEmpty())
        assertEquals(Phase.CLOSED, state.then(m(MessageType.ERLM), m(MessageType.ENTM)).phase)
    }

    @Test
    fun `Reklamation only after a completed fault repair (6_1_5)`() {
        val est = OrderLifecycle.submitted(BusinessCase.EST).then(m(MessageType.QEB))
        est.rejects(OrderEvent.Request(RequestKind.REKLAMATION))
        val reopened = est.then(m(MessageType.ERLM), m(MessageType.ENTM), OrderEvent.Request(RequestKind.REKLAMATION))
        assertEquals(Phase.SUBMITTED, reopened.phase)
        assertEquals(1, reopened.complaints)
    }

    @Test
    fun `provider change - handover only after QEB, in order`() {
        val pv = OrderLifecycle.submitted(BusinessCase.PV)
        pv.rejects(m(MessageType.AKM_PV))
        val announced = pv.then(m(MessageType.QEB), m(MessageType.AKM_PV))
        announced.rejects(m(MessageType.ABM_PV))
        assertEquals(HandoverPhase.REPLIED, announced.then(m(MessageType.RUEM_PV)).handover)
    }

    @Test
    fun `abgebender EKP only exchanges handover messages`() {
        OrderLifecycle.handoverAnnounced().then(m(MessageType.AKM_PV)).rejects(m(MessageType.QEB))
    }

    @Test
    fun `allowed events describe the next possible steps`() {
        val allowed = OrderLifecycle.allowedEvents(neu.then(m(MessageType.QEB)))
        assertTrue(m(MessageType.ABM) in allowed)
        assertTrue(storno in allowed)
        assertTrue(m(MessageType.ERLM) !in allowed)
    }
}
