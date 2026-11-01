package de.openspri.soap

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.message.MessageType
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SpriPayloadMapperTest {

    @Test
    fun `maps the sample NEU order`() {
        val order = SpriPayloadMapper.order(SecureXml.parse(TestData.sample("neu-auftrag.xml")).documentElement)
        assertEquals("TEST-0001", order.externalOrderNumber)
        assertEquals(BusinessCase.NEU, order.businessCase)
        assertEquals(ChangeIndicator.STANDARD, order.changeIndicator)
        assertEquals("AG4711", order.clientNumber)
        assertEquals("LN0815", order.serviceNumber)
        assertEquals(LocalDate.of(2026, 12, 1), order.desiredDate)
        assertEquals(listOf("FTTH L2 ETH 100.40"), order.products)
        assertEquals("ADDR:83395|musterstraße|1", order.infrastructureKey)
        assertFalse(order.connectivity)
    }

    @Test
    fun `maps a Terminverschiebung`() {
        val document = SecureXml.parse(TestData.sample("neu-auftrag.xml"))
        document.getElementsByTagNameNS("*", "aenderungskennzeichen").item(0).textContent = "Terminverschiebung"
        val auftrag = document.getElementsByTagNameNS("*", "auftrag").item(0)
        auftrag.appendChild(document.createElementNS(null, "terminNeu")).textContent = "2026-12-15"
        val order = SpriPayloadMapper.order(document.documentElement)
        assertEquals(ChangeIndicator.TERMINVERSCHIEBUNG, order.changeIndicator)
        assertEquals(LocalDate.of(2026, 12, 15), order.newDate)
    }

    @Test
    fun `maps the sample QEB`() {
        val message = SpriPayloadMapper.message(SecureXml.parse(TestData.sample("qeb-meldung.xml")).documentElement)!!
        assertEquals(MessageType.QEB, message.type)
        assertEquals(BusinessCase.NEU, message.businessCase)
        assertEquals("TEST-0001", message.externalOrderNumber)
        assertEquals(listOf("0000"), message.codes)
    }
}
