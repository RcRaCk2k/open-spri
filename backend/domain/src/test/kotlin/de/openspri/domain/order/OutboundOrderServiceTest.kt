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
            MessageCode("0010", "erledigt", setOf("ERLM"), setOf("NEU", "KUE-LE"), setOf("FTTH"), setOf("S/PRI")),
            MessageCode("8006", "Kündigung", setOf("KDLE"), setOf("KUE-LE"), setOf("FTTH"), setOf("S/PRI")),
        ),
    )
    private val drafts = de.openspri.domain.InMemoryOrderDrafts()
    private val service = OutboundOrderService(orders, outbox, { catalog }, DirectTransactions, clock, drafts = drafts)
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
    fun `a draft reserves the number and is queued only when it is sent`() {
        val draft = service.saveDraft(partner, data, "{}".toByteArray())
        assertEquals(Phase.DRAFT, draft.state.phase)
        assertTrue(outbox.entries.isEmpty())
        assertEquals(listOf<OrderEvent>(OrderEvent.Send), service.allowedActions(draft))
        assertContains(assertThrows<OrderActionRejected> { service.submitOrder(partner, data, ByteArray(0)) }.message!!, "bereits vergeben")
        assertContains(assertThrows<OrderActionRejected> { service.requestChange(draft, RequestKind.STORNO, data, ByteArray(0)) }.message!!, "Entwurf")

        val changed = service.saveDraft(partner, data.copy(desiredDate = LocalDate.of(2026, 12, 2)), "{\"v\":2}".toByteArray(), existing = draft)
        assertEquals(draft.id, changed.id)
        assertEquals("{\"v\":2}", String(service.draftContent(changed)!!))

        val sent = service.sendDraft(changed, changed.data, "<payload/>".toByteArray())
        assertEquals(Phase.SUBMITTED, sent.state.phase)
        assertEquals(sent.id, outbox.entries.single().orderId)
        assertEquals(null, service.draftContent(sent))
        assertContains(assertThrows<OrderActionRejected> { service.sendDraft(sent, sent.data, ByteArray(0)) }.message!!, "Nur ein Entwurf")
        assertContains(assertThrows<OrderActionRejected> { service.deleteDraft(sent) }.message!!, "Nur ein Entwurf")
    }

    @Test
    fun `a draft that breaks the order rules stays a draft, a deleted draft frees the number`() {
        val draft = service.saveDraft(partner, data.copy(desiredDate = LocalDate.of(2027, 8, 1)), "{}".toByteArray())
        assertContains(assertThrows<OrderActionRejected> { service.sendDraft(draft, draft.data, ByteArray(0)) }.message!!, "R-05")
        assertEquals(Phase.DRAFT, orders.get(draft.id!!)!!.state.phase)
        assertTrue(outbox.entries.isEmpty())

        service.deleteDraft(draft)
        assertTrue(orders.orders.isEmpty() && drafts.drafts.isEmpty())
        service.submitOrder(partner, data, ByteArray(0))
    }

    @Test
    fun `KDLE creates the KUE-LE order of the Leistungserbringer, ERLM and ENTM follow`() {
        val kdle = MessageInfo(MessageType.KDLE, BusinessCase.KUE_LE, ChangeIndicator.STANDARD, "LE-K-1", listOf("8006"))
        val data = OrderData("LE-K-1", BusinessCase.KUE_LE, contractNumber = "V1", clientNumber = "AG1")
        val order = service.terminate(partner, data, kdle, ByteArray(0))
        assertEquals(OrderRole.LEISTUNGSERBRINGER, order.role)
        assertEquals(Phase.CONFIRMED, order.state.phase)
        assertEquals(listOf(MessageType.KDLE), orders.history(order.id!!).map { it.messageType })
        assertEquals(order.id, outbox.entries.single().orderId)
        assertContains(assertThrows<OrderActionRejected> { service.terminate(partner, data, kdle, ByteArray(0)) }.message!!, "bereits vergeben")
        val wrongCode = kdle.copy(externalOrderNumber = "LE-K-2", codes = listOf("0000"))
        assertThrows<OrderActionRejected> { service.terminate(partner, data.copy(externalOrderNumber = "LE-K-2"), wrongCode, ByteArray(0)) }
        assertEquals(null, orders.find(partner, OrderRole.LEISTUNGSERBRINGER, "LE-K-2"), "nothing is stored on rejection")

        val erlm = service.sendMessage(order, kdle.copy(type = MessageType.ERLM, codes = listOf("0010")), ByteArray(0))
        assertEquals(Phase.COMPLETED, erlm.state.phase)
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
