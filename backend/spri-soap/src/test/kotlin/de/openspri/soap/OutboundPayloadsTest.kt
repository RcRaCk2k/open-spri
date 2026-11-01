package de.openspri.soap

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.message.MessageCodeTableParser
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.Order
import de.openspri.domain.order.OrderData
import de.openspri.domain.order.OrderId
import de.openspri.domain.order.OrderLifecycle
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.RequestKind
import de.openspri.domain.partner.PartnerId
import de.telekom.spri.oss.v4.envelope.AnnehmenAuftragRequestType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutboundPayloadsTest {

    private val mapper = JsonMapper.builder().build()
    private val payloads = OutboundPayloads(SpriSchema.load(), { TestData.ownKey })
    private val catalog = MessageCodeTableParser.parse(TestData.sample("message-codes.csv"), "message-codes.csv")

    private fun sampleOrderJson() = mapper.readTree(
        SpriJson.toJson(SpriJaxb.unmarshal(SecureXml.parse(TestData.sample("neu-auftrag.xml")).documentElement, AnnehmenAuftragRequestType::class.java)).toString(),
    ).get("auftrag")

    @Test
    fun `order from JSON gets our control header and is checked`() {
        val (built, data) = payloads.order(sampleOrderJson())
        val xml = String(built.bytes)
        assertEquals("TEST-0001", data.externalOrderNumber)
        assertEquals(BusinessCase.NEU, data.businessCase)
        assertContains(xml, TestData.ownKey.certificate.serialNumber.toString())
        assertContains(xml, "<majorRelease>4</majorRelease>")
    }

    @Test
    fun `invalid order JSON is rejected with the path`() {
        val json = sampleOrderJson().deepCopy() as tools.jackson.databind.node.ObjectNode
        json.put("aenderungskennzeichen", "Sofort")
        val error = assertThrows<PayloadInvalidException> { payloads.order(json) }
        assertContains(error.errors.single(), "$.auftrag.aenderungskennzeichen")
    }

    @Test
    fun `storno and terminverschiebung are derived from the original order`() {
        val (original, _) = payloads.order(sampleOrderJson())
        val (storno, stornoData) = payloads.change(original.bytes, RequestKind.STORNO, null)
        assertEquals(ChangeIndicator.STORNO, stornoData.changeIndicator)
        assertContains(String(storno.bytes), "Storno")

        val date = LocalDate.now().plusDays(30)
        val (_, tv) = payloads.change(original.bytes, RequestKind.TERMINVERSCHIEBUNG, date)
        assertEquals(ChangeIndicator.TERMINVERSCHIEBUNG, tv.changeIndicator)
        assertEquals(date, tv.newDate)
    }

    @Test
    fun `simplified message takes header and numbers from the order`() {
        val order = Order(
            OrderId(1), PartnerId(1), OrderRole.LEISTUNGSERBRINGER,
            OrderData("TEST-0001", BusinessCase.NEU, clientNumber = "AG4711"),
            OrderLifecycle.submitted(BusinessCase.NEU), Instant.now(), Instant.now(),
        )
        val (built, info) = payloads.message(order, MessageType.QEB, ChangeIndicator.STANDARD, listOf("0000" to null), null, catalog)
        assertEquals(MessageType.QEB, info.type)
        assertEquals(listOf("0000"), info.codes)
        val xml = String(built.bytes)
        assertContains(xml, "<externeAuftragsnummer>TEST-0001</externeAuftragsnummer>")
        assertContains(xml, "<auftraggebernummer>AG4711</auftraggebernummer>")
        assertTrue(xml.contains("<geschaeftsfallart>Bereitstellung</geschaeftsfallart>"))
    }
}
