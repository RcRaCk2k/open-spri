package de.openspri.domain.order

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.message.MessageCode
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageType
import de.openspri.domain.partner.PartnerId
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InboundOrderServiceTest {

    private class InMemoryOrders : OrderRepository {
        val orders = mutableListOf<Order>()
        val history = mutableListOf<OrderHistoryEntry>()
        override fun find(partnerId: PartnerId, role: OrderRole, externalOrderNumber: String) =
            orders.firstOrNull { it.partnerId == partnerId && it.role == role && it.data.externalOrderNumber == externalOrderNumber }
        override fun findAnyPartner(role: OrderRole, externalOrderNumber: String, businessCase: BusinessCase) =
            orders.filter { it.role == role && it.data.externalOrderNumber == externalOrderNumber && it.data.businessCase == businessCase }
        override fun hasOpenOrder(role: OrderRole, infrastructureKey: String, except: OrderId?) =
            orders.any { it.role == role && it.data.infrastructureKey == infrastructureKey && !it.state.finished && it.id != except }
        override fun save(order: Order): Order {
            val stored = if (order.id == null) order.copy(id = OrderId(orders.size + 1L)) else order
            orders.removeIf { it.id == stored.id }
            orders += stored
            return stored
        }
        override fun append(entry: OrderHistoryEntry) { history += entry }
    }

    private val partner = PartnerId(1)
    private val ekpab = PartnerId(2)
    private val repository = InMemoryOrders()
    private val clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val catalog = MessageCodeCatalog(
        "test", "0",
        listOf(
            MessageCode("0000", "ok", setOf("QEB", "ABM"), setOf("NEU"), setOf("FTTH"), setOf("S/PRI")),
            MessageCode("0010", "erledigt", setOf("ERLM", "ENTM"), setOf("NEU"), setOf("FTTH"), setOf("S/PRI")),
        ),
    )
    private val service = InboundOrderService(repository, { catalog }, clock)

    private fun neu(number: String = "A-1", date: LocalDate = LocalDate.of(2026, 12, 1), key: String? = "ADDR:x") =
        OrderData(number, BusinessCase.NEU, desiredDate = date, products = listOf("FTTH 100"), infrastructureKey = key)

    private fun message(type: MessageType, number: String = "A-1", indicator: ChangeIndicator = ChangeIndicator.STANDARD, vararg codes: String) =
        MessageInfo(type, BusinessCase.NEU, indicator, number, codes.toList())

    @Test
    fun `new order is stored as submitted`() {
        val result = service.receiveOrder(partner, neu(), archiveId = 10)
        assertEquals(emptyList(), result.findings)
        assertEquals(Phase.SUBMITTED, repository.orders.single().state.phase)
        assertEquals(10L, repository.history.single().archiveId)
    }

    @Test
    fun `repeated order is a duplicate (11_4) and does not create a second order`() {
        service.receiveOrder(partner, neu(), 1)
        assertContains(service.receiveOrder(partner, neu(), 2).findings.single(), "1010")
        assertEquals(1, repository.orders.size)
    }

    @Test
    fun `R-05 date more than 180 days ahead`() {
        assertContains(service.receiveOrder(partner, neu(date = LocalDate.of(2027, 6, 1)), 1).findings.single(), "R-05")
    }

    @Test
    fun `R-10 open order for the same infrastructure`() {
        service.receiveOrder(partner, neu("A-1"), 1)
        assertContains(service.receiveOrder(partner, neu("A-2"), 2).findings.single(), "R-10")
    }

    @Test
    fun `Terminverschiebung moves the date, an equal date is reported (R-02)`() {
        service.receiveOrder(partner, neu(), 1)
        repository.save(repository.orders.single().let { it.copy(state = it.state.copy(phase = Phase.ACKNOWLEDGED)) })
        val reschedule = neu().copy(changeIndicator = ChangeIndicator.TERMINVERSCHIEBUNG, newDate = LocalDate.of(2026, 12, 10))
        assertEquals(emptyList(), service.receiveOrder(partner, reschedule, 2).findings)
        val order = repository.orders.single()
        assertEquals(LocalDate.of(2026, 12, 10), order.data.desiredDate)
        assertTrue(RequestKind.TERMINVERSCHIEBUNG in order.state.openRequests)

        val same = reschedule.copy(newDate = LocalDate.of(2026, 12, 10))
        assertTrue(service.receiveOrder(partner, same, 3).findings.any { "R-03" in it || "R-02" in it })
    }

    @Test
    fun `messages advance the order of the Auftraggeber and check message codes`() {
        val order = repository.save(Order(null, partner, OrderRole.AUFTRAGGEBER, neu(), OrderLifecycle.submitted(BusinessCase.NEU), clock.instant(), clock.instant()))
        assertEquals(emptyList(), service.receiveMessage(partner, message(MessageType.QEB, codes = arrayOf("0000")), 1).findings)
        assertContains(service.receiveMessage(partner, message(MessageType.ABM, codes = arrayOf("0010")), 2).findings.single(), "R-13")
        assertEquals(Phase.CONFIRMED, repository.orders.single { it.id == order.id }.state.phase)
    }

    @Test
    fun `message out of order is recorded with a finding, the state stays`() {
        repository.save(Order(null, partner, OrderRole.AUFTRAGGEBER, neu(), OrderLifecycle.submitted(BusinessCase.NEU), clock.instant(), clock.instant()))
        val result = service.receiveMessage(partner, message(MessageType.ERLM, codes = arrayOf("0010")), 1)
        assertContains(result.findings.single(), "Ablauf")
        assertEquals(Phase.SUBMITTED, repository.orders.single().state.phase)
        assertEquals(MessageType.ERLM, repository.history.single().messageType)
    }

    @Test
    fun `AKM-PV opens the view of the abgebender EKP, KDLE opens a KUE-LE order`() {
        service.receiveMessage(ekpab, MessageInfo(MessageType.AKM_PV, BusinessCase.PV, ChangeIndicator.STANDARD, "PV-9", emptyList(), contractNumber = "V123"), 1)
        assertEquals(OrderRole.ABGEBENDER_EKP, repository.orders.single().role)
        assertEquals(HandoverPhase.ANNOUNCED, repository.orders.single().state.handover)

        service.receiveMessage(partner, MessageInfo(MessageType.KDLE, BusinessCase.KUE_LE, ChangeIndicator.STANDARD, "LE-7", emptyList()), 2)
        assertEquals(Phase.CONFIRMED, repository.orders.single { it.data.externalOrderNumber == "LE-7" }.state.phase)
    }

    @Test
    fun `message for an unknown order is recorded`() {
        assertContains(service.receiveMessage(partner, message(MessageType.QEB, "X-404"), 1).findings.single(), "unbekanntem Auftrag")
        assertEquals(null, repository.history.single().orderId)
    }

    @Test
    fun `R-15 LineID format`() {
        assertTrue(OrderRules.isValidLineId("DEU.DTAG.1754639821"))
        assertTrue(!OrderRules.isValidLineId("DE.DTAG.1"))
        assertTrue(!OrderRules.isValidLineId("DEU.dtag.1"))
        assertTrue(!OrderRules.isValidLineId("DEU.DTAG.12345678901"))
    }
}
