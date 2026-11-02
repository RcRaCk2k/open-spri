package de.openspri.replikat

import de.openspri.domain.availability.Location
import de.openspri.domain.availability.OrderKind
import de.openspri.domain.availability.ProductAvailability
import de.openspri.domain.availability.TechnologyAvailability
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Replikat 1.7 chapter 4: which Replikat field goes where in the order. */
class ReplikatOrderTemplateTest {
    private val location = Location(
        addressId = "A-1", postalCode = "12345", city = "Musterstadt", district = "Mitte", street = "Musterstraße", houseNumber = "16", houseNumberSuffix = "20",
        buildingPart = "Hinterhaus",
        products = listOf(ProductAvailability("FTTH 100", "FTTH"), ProductAvailability("Feste IP-Adresse", "FTTH")),
        technologies = listOf(TechnologyAvailability("FTTH", homeIds = listOf("HID1", "HID2"), orderKind = OrderKind.KONNEKTIVITAET, ownerDataRequired = true, timeWindows = listOf("27", "3"))),
    )

    @Test
    fun `address, HomeID, products, time window and Konnektivität are taken over`() {
        val result = ReplikatOrderTemplate.of(location, listOf("FTTH 100", "Feste IP-Adresse"), homeId = "HID2")
        val gf = result.auftrag.path("geschaeftsfall")
        val fttx = gf.path("auftragsposition").path("geschaeftsfallProdukt")
        assertEquals("16", fttx.path("standortA").path("strasse").path("hausnummer").stringValue())
        assertEquals("20", fttx.path("standortA").path("strasse").path("hausnummernZusatz").stringValue())
        assertEquals("Mitte", fttx.path("standortA").path("ort").path("ortsteil").stringValue())
        assertEquals("Hinterhaus", fttx.path("standortA").path("gebaeudeteil").path("gebaeudeteilName").stringValue())
        assertEquals("HID2", fttx.path("vormieter").path("homeId").path("homeIdNummer").stringValue())
        assertEquals("FTTH 100", gf.path("auftragsposition").path("produkt").path("bezeichner").stringValue())
        assertEquals(listOf("Feste IP-Adresse", "Konnektivität"), gf.path("auftragsposition").path("position").toList().map { it.path("produkt").path("bezeichner").stringValue() })
        assertEquals("3", gf.path("termine").path("auftraggeberWunschtermin").path("zeitfenster").stringValue(), "27 is not an S/PRI time window")
        assertContains(result.hints.joinToString(), "Eigentümerdaten")
        assertContains(result.missing, "externeAuftragsnummer")
    }

    @Test
    fun `products and HomeIDs must belong to the location`() {
        assertThrows<IllegalArgumentException> { ReplikatOrderTemplate.of(location, listOf("FTTH 1000")) }
        assertThrows<IllegalArgumentException> { ReplikatOrderTemplate.of(location, homeId = "HID9") }
        assertContains(ReplikatOrderTemplate.of(location).missing.joinToString(), "mehrere HomeIDs")
    }
}
