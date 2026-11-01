package de.openspri.domain.partner

import de.openspri.domain.BusinessCase
import de.openspri.domain.InMemoryAgreements
import de.openspri.domain.InMemoryPartners
import de.openspri.domain.RecordingNotifier
import de.openspri.domain.order.OrderData
import de.openspri.domain.order.OrderRole
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgreementsTest {

    private val partner = PartnerId(5)
    private val today = LocalDate.of(2026, 11, 2)
    private val store = InMemoryAgreements()
    private val rules = AgreementRules(store, store)
    private val order = OrderData("A-1", BusinessCase.NEU, clientNumber = "AG1", serviceNumber = "LN1")

    @Test
    fun `an order in the name of a third party needs a valid power of attorney (R-11)`() {
        val thirdParty = order.copy(ordererClientNumber = "B1", ordererServiceNumber = "BL1")
        assertContains(rules.checkInbound(partner, thirdParty, today).single(), "R-11")
        store.savePowerOfAttorney(PowerOfAttorney(null, partner, "B1", "BL1", "AG1", "LN1", validity = Validity(to = today.minusDays(1))))
        assertContains(rules.checkInbound(partner, thirdParty, today).single(), "R-11")
        store.savePowerOfAttorney(PowerOfAttorney(null, partner, "B1", "BL1", "AG1", "LN1", validity = Validity(from = today)))
        assertEquals(emptyList(), rules.checkInbound(partner, thirdParty, today))
        assertContains(rules.checkInbound(PartnerId(6), thirdParty, today).single(), "R-11")
    }

    @Test
    fun `a Projektkenner needs a project agreement (R-12)`() {
        val project = order.copy(projectId = "P-1")
        assertContains(rules.checkProject(partner, project, today).single(), "R-12")
        store.saveProject(ProjectAgreement(null, partner, "P-1", validity = Validity(today, today.plusDays(30))))
        assertEquals(emptyList(), rules.checkInbound(partner, project, today))
        assertEquals(emptyList(), rules.checkProject(partner, order, today))
    }

    @Test
    fun `contracts and products are only checked once maintained`() {
        assertEquals(emptyList(), rules.checkInbound(partner, order.copy(products = listOf("FTTH 100")), today))
        store.saveContract(FrameworkContract(null, partner, OrderRole.LEISTUNGSERBRINGER, "AG2"))
        store.save(Product(null, null, OrderRole.LEISTUNGSERBRINGER, "FTTH 50"))
        val findings = rules.checkInbound(partner, order.copy(products = listOf("FTTH 100", "Konnektivität")), today)
        assertEquals(2, findings.size, findings.toString())
        assertContains(findings[0], "Rahmenvertrag")
        assertContains(findings[1], "FTTH 100")
        store.saveContract(FrameworkContract(null, partner, OrderRole.LEISTUNGSERBRINGER, "AG1", "LN1"))
        assertEquals(emptyList(), rules.checkInbound(partner, order.copy(products = listOf("ftth 50")), today))
    }

    @Test
    fun `validity is inclusive and must not end before it starts`() {
        assertTrue(Validity(today, today).covers(today))
        assertThrows<IllegalArgumentException> { Validity(today, today.minusDays(1)) }
    }

    @Test
    fun `product change matrix in the layout of Anlage 6`() {
        val csv = """
            "Produktwechselmatrix (Testdaten)",,,
            Bestand,Ziel,,
            ,FTTx 10,FTTx 20 ,FTTx 10 TP
            FTTH 10,,LAE,AEN-LMAE
            FTTH 20,LAE,,LAE
            ,,,
            LAE,Leistungsänderung,,
        """.trimIndent()
        val changes = ProductChangeMatrixParser.parse(csv)
        assertEquals(4, changes.size)
        assertEquals(ProductChange(null, "FTTH 10", "FTTH 20", BusinessCase.LAE), changes[0])
        assertEquals(ProductChange(null, "FTTH 10", "FTTH 10 TP", BusinessCase.AEN_LMAE), changes[1])
        assertContains(assertThrows<IllegalArgumentException> { ProductChangeMatrixParser.parse(",A,B\nFTTH 10,LAE,NEU") }.message!!, "NEU")
        assertThrows<IllegalArgumentException> { ProductChangeMatrixParser.parse("nur Text") }
    }

    @Test
    fun `maintenance is announced to every enabled partner with contact, short notice is a warning (NF-08)`() {
        val partners = InMemoryPartners()
        val withMail = partners.create(Partner(PartnerId(0), "Nord", "NORD", emptyList(), emptyList(), technicalContactEmail = "noc@nord.example"))
        partners.create(Partner(PartnerId(0), "Süd", "SUED", emptyList(), emptyList()))
        partners.create(Partner(PartnerId(0), "Aus", "AUS", emptyList(), emptyList(), enabled = false, technicalContactEmail = "noc@aus.example"))
        val notifier = RecordingNotifier()
        // Monday 2026-11-02, 10:00
        val clock = Clock.fixed(Instant.parse("2026-11-02T09:00:00Z"), ZoneId.of("Europe/Berlin"))
        val service = MaintenanceService(partners, notifier, store, clock)

        val early = service.announce(Instant.parse("2026-11-10T20:00:00Z"), Instant.parse("2026-11-10T22:00:00Z"), "Wartung", "Update", null, "admin")
        assertEquals(1, early.recipients)
        assertEquals(listOf("Nord"), notifier.maintenance.map { it.first.name })
        assertEquals(1, early.warnings.size, early.warnings.toString())
        assertContains(early.warnings.single(), "Süd")

        val late = service.announce(Instant.parse("2026-11-06T20:00:00Z"), Instant.parse("2026-11-06T22:00:00Z"), "Wartung", "Update", listOf(withMail), "admin")
        assertContains(late.warnings.single(), "NF-08")
        assertThrows<IllegalArgumentException> { service.announce(Instant.parse("2026-11-20T20:00:00Z"), Instant.parse("2026-11-20T19:00:00Z"), "W", "T", null, "admin") }
        assertEquals(5, MaintenanceService.workingDaysBetween(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 10)))
    }
}
