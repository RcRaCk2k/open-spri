package de.openspri.domain.order

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.DirectTransactions
import de.openspri.domain.InMemoryInventory
import de.openspri.domain.InMemoryOrderRepository
import de.openspri.domain.InMemoryOutbox
import de.openspri.domain.message.MessageCode
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageType
import de.openspri.domain.partner.PartnerId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InventoryBookTest {
    private val now = Instant.parse("2026-11-01T10:00:00Z")
    private val store = InMemoryInventory()
    private val book = InventoryBook(store)
    private val partner = PartnerId(3)

    private fun order(case: BusinessCase, phase: Phase, cancelled: Boolean = false, line: String = "DEU.LE.1", contract: String = "V1", role: OrderRole = OrderRole.LEISTUNGSERBRINGER) =
        Order(OrderId(1), partner, role, OrderData("A-1", case, lineId = line, contractNumber = contract, clientNumber = "AG1"),
            OrderState(case, phase, cancelled = cancelled), now, now)

    @Test
    fun `a completed NEU creates a contract, a new one on the same line ends it, KUE ends it`() {
        book.onChange(order(BusinessCase.NEU, Phase.COMPLETED), order(BusinessCase.NEU, Phase.CLOSED), now)
        assertEquals("V1", store.active(OrderRole.LEISTUNGSERBRINGER, "DEU.LE.1")!!.contractNumber)
        assertEquals("AG1", book.holder("DEU.LE.1")!!.clientNumber)

        book.onChange(order(BusinessCase.PV, Phase.COMPLETED, contract = "V2"), order(BusinessCase.PV, Phase.CLOSED, contract = "V2"), now)
        assertEquals(listOf("V2"), store.contracts.filter { it.active }.map { it.contractNumber })

        book.onChange(order(BusinessCase.KUE_AG, Phase.COMPLETED, contract = "V2"), order(BusinessCase.KUE_AG, Phase.CLOSED, contract = "V2"), now)
        assertNull(store.active(OrderRole.LEISTUNGSERBRINGER, "DEU.LE.1"))
    }

    @Test
    fun `cancelled orders and other roles do not touch the contract`() {
        book.onChange(order(BusinessCase.NEU, Phase.COMPLETED), order(BusinessCase.NEU, Phase.CLOSED, cancelled = true), now)
        assertEquals(0, store.contracts.size)
        book.onChange(order(BusinessCase.NEU, Phase.COMPLETED, role = OrderRole.AUFTRAGGEBER), order(BusinessCase.NEU, Phase.CLOSED, role = OrderRole.AUFTRAGGEBER), now)
        assertNull(book.holder("DEU.LE.1"), "our own line as Auftraggeber is no handover partner")
        assertEquals(OrderRole.AUFTRAGGEBER, store.contracts.single().role)
    }

    @Test
    fun `as abgebender EKP the completed handover ends our contract`() {
        book.onChange(order(BusinessCase.NEU, Phase.COMPLETED, role = OrderRole.AUFTRAGGEBER), order(BusinessCase.NEU, Phase.CLOSED, role = OrderRole.AUFTRAGGEBER), now)
        val handover = Order(OrderId(2), partner, OrderRole.ABGEBENDER_EKP, OrderData("PV-1", BusinessCase.PV, lineId = "DEU.LE.1", contractNumber = "V1"),
            OrderLifecycle.handoverAnnounced().copy(handover = HandoverPhase.COMPLETED), now, now)
        book.onChange(handover, handover.copy(state = handover.state.copy(handover = HandoverPhase.CLOSED)), now)
        assertNull(store.active(OrderRole.AUFTRAGGEBER, "DEU.LE.1"))
    }

    @Test
    fun `provider change as Leistungserbringer goes to the holder of the contract (5_5_1)`() {
        val catalog = MessageCodeCatalog("t", "0", listOf(
            MessageCode("0000", "ok", setOf("QEB"), emptySet(), emptySet(), setOf("S/PRI")),
            MessageCode("0020", "AKM", setOf("AKM-PV"), emptySet(), emptySet(), setOf("S/PRI")),
        ))
        val orders = InMemoryOrderRepository()
        val outbox = InMemoryOutbox()
        val service = OutboundOrderService(orders, outbox, { catalog }, DirectTransactions, Clock.fixed(now, ZoneId.of("UTC")), inventory = book)
        val pv = orders.save(Order(null, PartnerId(7), OrderRole.LEISTUNGSERBRINGER, OrderData("PV-2", BusinessCase.PV, lineId = "DEU.LE.1", clientNumber = "AGNEU"),
            OrderLifecycle.submitted(BusinessCase.PV).copy(phase = Phase.ACKNOWLEDGED), now, now))
        val akm = MessageInfo(MessageType.AKM_PV, BusinessCase.PV, ChangeIndicator.STANDARD, "PV-2", listOf("0020"))
        assertContains(assertThrows<OrderActionRejected> { service.sendMessage(pv, akm, ByteArray(0)) }.message!!, "abgebende EKP ist unbekannt")

        store.add(InventoryContract(null, OrderRole.LEISTUNGSERBRINGER, partner, "V9", "DEU.LE.1", "AGALT", null, emptyList(), null, now))
        val sent = service.sendMessage(pv, akm, ByteArray(0))
        assertEquals(HandoverParty(partner, "V9", "AGALT"), sent.data.handover)
        assertNull(sent.data.contractNumber, "the contract of the abgebender EKP is not ours")
        assertEquals(partner, outbox.entries.single().partnerId)
        assertEquals(HandoverPhase.ANNOUNCED, sent.state.handover)
    }
}
