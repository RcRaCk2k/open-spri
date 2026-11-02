package de.openspri.replikat

import de.openspri.domain.availability.ChangeMark
import de.openspri.domain.availability.Location
import de.openspri.domain.availability.OrderKind
import de.openspri.domain.availability.ProductAvailability
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M6 acceptance (AUFGABE 11): Replikat.xsd 1.7 and the example files of Anlage 3. */
class ReplikatXmlTest {

    private fun example(name: String) = requireNotNull(javaClass.classLoader.getResourceAsStream("replikat-examples/$name.xml")) { "$name fehlt" }.readAllBytes()

    private fun read(bytes: ByteArray): Pair<ReplikatHeader, List<Location>> {
        val records = mutableListOf<Location>()
        val header = ReplikatXml.read(ByteArrayInputStream(bytes)) { records += it }
        return header to records
    }

    @TestFactory
    fun `examples of Anlage 3 are valid, readable and survive a roundtrip`(): List<DynamicTest> = listOf("Standard", "Konnektivitaet", "Migration").map { name ->
        DynamicTest.dynamicTest(name) {
            val bytes = example(name)
            assertEquals(emptyList(), ReplikatXml.validate(ByteArrayInputStream(bytes)))
            val (header, records) = read(bytes)
            assertEquals("NGABY", header.carrierCode)
            assertTrue(records.isNotEmpty())

            val written = ByteArrayOutputStream().also { ReplikatXml.write(it, header, records.asSequence()) }.toByteArray()
            assertEquals(emptyList(), ReplikatXml.validate(ByteArrayInputStream(written)), String(written))
            assertEquals(header to records, read(written))
        }
    }

    @Test
    fun `fields of the Konnektivität example are mapped`() {
        val location = read(example("Konnektivitaet")).second.first()
        assertEquals(ChangeMark.UCH, location.change)
        assertEquals("10000001", location.addressId)
        assertEquals("10589", location.postalCode)
        val technology = location.technologies.single()
        assertEquals(OrderKind.KONNEKTIVITAET, technology.orderKind)
        assertEquals(true, technology.ownerDataRequired)
        assertEquals(LocalDate.of(2025, 1, 1), technology.marketingStart)
        assertEquals("Initialausbau", technology.expansionPhase)
        assertEquals(LocalDate.of(2020, 10, 13), location.products.single().availableFrom)
    }

    @Test
    fun `an invalid Replikat is reported with line numbers`() {
        val invalid = String(example("Standard")).replace("<n1:plz>10589</n1:plz>", "<n1:plz>ABCDE</n1:plz>")
        val errors = ReplikatXml.validate(ByteArrayInputStream(invalid.toByteArray()))
        assertTrue(errors.isNotEmpty())
        assertContains(errors.first(), "Zeile")
    }

    @Test
    fun `package with ZIP, meta file and md5`() {
        val location = Location(postalCode = "12345", city = "Musterstadt", street = "Musterstraße", houseNumber = "1", products = listOf(ProductAvailability("FTTH 100")))
        val header = ReplikatHeader(LocalDate.of(2026, 11, 2), "TSTNZ", delta = false)
        val pkg = ReplikatFiles.pack(header, sequenceOf(location), Instant.parse("2026-11-02T04:00:00Z"), ZoneId.of("Europe/Berlin"))
        assertEquals("TSTNZ_20261102.zip", pkg.fileName)
        assertEquals("TSTNZ_20261102_meta.xml", pkg.metaFileName)
        assertEquals(ReplikatFiles.md5(pkg.content), pkg.meta.md5)
        assertEquals(pkg.meta, ReplikatMeta.parse(pkg.meta.toXml()))
        assertEquals(listOf(location), read(ReplikatFiles.xml(pkg.content).readAllBytes()).second)
        assertEquals("TSTNZ_20261102_delta", ReplikatFiles.baseName("TSTNZ", header.date, delta = true))
    }
}
