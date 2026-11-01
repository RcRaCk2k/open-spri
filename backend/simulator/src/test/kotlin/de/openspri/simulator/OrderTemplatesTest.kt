package de.openspri.simulator

import de.openspri.domain.BusinessCase
import de.openspri.soap.OutboundPayloads
import de.openspri.soap.PayloadInvalidException
import de.openspri.soap.SigningCredentials
import de.openspri.soap.SpriSchema
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Every order template is valid against the XSD and Anlage 1 and maps to the intended business case. */
class OrderTemplatesTest {
    private val payloads = OutboundPayloads(SpriSchema.load(), { KEY })
    private val inventory = OrderTemplates.Inventory("V0000001", "DEU.SIMLE.0000000001")
    private val date = LocalDate.now().plusDays(30)

    @TestFactory
    fun `templates are valid orders`(): List<DynamicTest> = OrderTemplates.supported.map { case ->
        DynamicTest.dynamicTest(case.code) {
            val (_, data) = try {
                payloads.order(OrderTemplates.order(case, "T-${case.code}", date, 7, inventory))
            } catch (e: PayloadInvalidException) {
                fail("${case.code}: ${e.errors}")
            }
            assertEquals(case, data.businessCase)
            assertEquals("T-${case.code}", data.externalOrderNumber)
            if (case in OrderTemplates.needsInventory) assertEquals(inventory.lineId, data.lineId)
        }
    }

    @Test
    fun `NEU with all order variants is valid and carries them`() {
        val variants = setOf(Variant.PROJECT, Variant.ORDER_MARKER, Variant.THIRD_PARTY, Variant.PRE_AGREEMENT, Variant.HOME_ID, Variant.CONNECTIVITY)
        val (_, data) = payloads.order(OrderTemplates.order(BusinessCase.NEU, "T-VAR-1234", date, 3, variants = variants, owner = true))
        assertEquals(OrderTemplates.PROJECT_ID, data.projectId)
        assertEquals("1234", data.bracketId)
        assertEquals(OrderTemplates.ORDERER.clientNumber, data.ordererClientNumber)
        assertTrue(data.preAgreementId!!.length in 16..21)
        assertTrue(data.homeId!!.startsWith("HID"))
        assertTrue(data.connectivity)
    }

    @Test
    fun `complaint is an EST order with reklamation`() {
        val (_, data) = payloads.order(OrderTemplates.complaint("T-REK", date, inventory, "LE-ST-1"))
        assertTrue(data.complaint)
    }

    private companion object {
        val KEY = SigningCredentials.generateSelfSigned("Vorlagen-Test")
    }
}
