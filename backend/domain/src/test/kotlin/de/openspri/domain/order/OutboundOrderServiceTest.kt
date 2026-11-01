package de.openspri.domain.order

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.DirectTransactions
import de.openspri.domain.InMemoryOrderRepository
import de.openspri.domain.InMemoryOutbox
import de.openspri.domain.delivery.RequestType
import de.openspri.domain.message.MessageCode
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageType
import de.openspri.domain.partner.PartnerId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutboundOrderServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val orders = InMemoryOrderRepository()
    private val outbox = InMemoryOutbox()
    private val catalog = MessageCodeCatalog(
        "test", "0",
        listOf(
            MessageCode("0000", "ok", setOf("QEB", "ABM"), setOf("NEU"), setOf("FTTH"), setOf("S/PRI")),
            MessageCode("0010", "erledigt", setOf("ERLM"), setOf("NEU"), setOf("FTTH"), setOf("S/PRI")),
        ),
    )
    private val service = OutboundOrderService(orders, outbox, { catalog }, DirectTransactions, clock)
    private val partner = PartnerId(3)
    private val data = OrderData("AG-1", BusinessCase.NEU, desiredDate = LocalDate.of(2026, 12, 1))

    private fun leOrder() = orders.save(
        Order(null, partner, OrderRole.LEISTUNGSERBRINGER, data, OrderLifecycle.submitted(BusinessCase.NEU), clock.instant(), clock.instant()),
    )

    private fun msg(type: MessageType, vararg codes: String) = MessageInfo(type, BusinessCase.NEU, ChangeIndicator.STANDARD, "AG-1", codes.toList())

    @Test
    fun `own order is stored and queued in one step`() {
        val order = service.submitOrder(partner, data, "<payload/>".toByteArray())
        val entry = outbox.entries.single()
        assertEquals(RequestType.ORDER, entry.type)
        assertEquals(order.id, entry.orderId)
        assertEquals("3:ORDER:AG-1", entry.sequenceKey)
    }

    @Test
    fun `own order with rule violations is not queued`() {
        val error = assertThrows<OrderActionRejected> { service.submitOrder(partner, data.copy(desiredDate = LocalDate.of(2027, 8, 1)), ByteArray(0)) }
        assertContains(error.message!!, "R-05")
        assertTrue(outbox.entries.isEmpty() && orders.orders.isEmpty())
    }

    @Test
    fun `Leistungserbringer messages follow the lifecycle strictly`() {
        val order = leOrder()
        assertContains(assertThrows<OrderActionRejected> { service.sendMessage(order, msg(MessageType.ABM, "0000"), ByteArray(0)) }.message!!, "ABM")
        val acknowledged = service.sendMessage(order, msg(MessageType.QEB, "0000"), ByteArray(0))
        assertEquals(Phase.ACKNOWLEDGED, acknowledged.state.phase)
        assertEquals(1, outbox.entries.size)
    }

    @Test
    fun `wrong message code, missing code and wrong sender are rejected`() {
        val order = leOrder()
        assertContains(assertThrows<OrderActionRejected> { service.sendMessage(order, msg(MessageType.QEB, "0010"), ByteArray(0)) }.message!!, "R-13")
        assertContains(assertThrows<OrderActionRejected> { service.sendMessage(order, msg(MessageType.QEB), ByteArray(0)) }.message!!, "11.2.4")
        val own = service.submitOrder(partner, data.copy(externalOrderNumber = "AG-2"), ByteArray(0))
        assertContains(assertThrows<OrderActionRejected> { service.sendMessage(own, msg(MessageType.QEB, "0000"), ByteArray(0)) }.message!!, "wir sind AUFTRAGGEBER")
    }

    @Test
    fun `the sequence key stays the same when the LineID becomes known with ABM`() {
        var order = leOrder()
        order = service.sendMessage(order, msg(MessageType.QEB, "0000"), ByteArray(0))
        service.sendMessage(order, msg(MessageType.ABM, "0000").copy(lineId = "DEU.TEST.1"), ByteArray(0))
        assertEquals(1, outbox.entries.map { it.sequenceKey }.distinct().size)
    }
}
