package de.openspri.replikat

import de.openspri.domain.InMemoryPartnerAvailability
import de.openspri.domain.availability.AvailabilityQuery
import de.openspri.domain.availability.AvailabilitySource
import de.openspri.domain.availability.ChangeMark
import de.openspri.domain.availability.ImportStatus
import de.openspri.domain.availability.Location
import de.openspri.domain.availability.ProductAvailability
import de.openspri.domain.availability.SourceKind
import de.openspri.domain.availability.AddressFilter
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerId
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ReplikatImporterTest {
    private val partner = Partner(PartnerId(9), "Glasfaser Nord", "GFNORD", emptyList(), emptyList())
    private val store = InMemoryPartnerAvailability()
    private val files = mutableMapOf<String, ByteArray>()
    private val client = object : SourceClient() {
        override fun get(source: AvailabilitySource, url: URI) = files[url.path.substringAfterLast('/')]
    }
    private var now = Instant.parse("2026-11-02T06:30:00Z")
    private val zone = ZoneId.of("Europe/Berlin")
    private fun importer() = ReplikatImporter(store, store, client, Clock.fixed(now, zone))
    private val source = store.save(AvailabilitySource(null, partner.id, SourceKind.REPLIKAT, "https://spri.nord.example/replikat/", delta = true))

    private fun location(house: String, product: String = "FTTH 100", lat: String = "52.5", long: String = "13.3", change: ChangeMark? = null) = Location(
        change = change, addressId = "N-$house", latitude = lat, longitude = long, postalCode = "12345", city = "Musterstadt", street = "Nordweg",
        houseNumber = house, products = listOf(ProductAvailability(product)),
    )

    private fun provide(date: LocalDate, delta: Boolean, records: List<Location>, corruptMd5: Boolean = false) {
        val pkg = ReplikatFiles.pack(ReplikatHeader(date, "GFNORD", delta), records.asSequence(), now, zone)
        files[pkg.fileName] = pkg.content
        files[pkg.metaFileName] = (if (corruptMd5) pkg.meta.copy(md5 = "0".repeat(32)) else pkg.meta).toXml()
    }

    private fun houses() = store.search(partner.id, AvailabilityQuery(AddressFilter(postalCode = "12345"))).map { it.houseNumber }.sorted()

    @Test
    fun `full import, then the delta of the next day, a delta is never applied twice`() {
        provide(LocalDate.of(2026, 11, 2), false, listOf(location("1"), location("2")))
        val first = importer().run(partner, source)
        assertEquals(ImportStatus.OK, first.status, first.error)
        assertEquals(listOf("1", "2"), houses())

        now = now.plusSeconds(86_400)
        provide(LocalDate.of(2026, 11, 3), false, listOf(location("1", "FTTH 250"), location("3")))
        provide(LocalDate.of(2026, 11, 3), true, listOf(location("1", "FTTH 250", change = ChangeMark.MOD), location("2", change = ChangeMark.DEL), location("3", change = ChangeMark.ADD)))
        val second = importer().run(partner, source)
        assertEquals(true, second.delta)
        assertEquals(Triple(1, 1, 1), Triple(second.added, second.changed, second.deleted))
        assertEquals(listOf("1", "3"), houses())
        assertEquals("GFNORD_20261103_delta.zip", second.fileName)

        val again = importer().run(partner, source)
        assertEquals(listOf("bereits importiert"), again.warnings)
        assertEquals(listOf("1", "3"), houses())
    }

    @Test
    fun `yesterday's Replikat if today's is not there yet, MD5 and plausibility checked`() {
        provide(LocalDate.of(2026, 11, 1), false, listOf(location("1"), location("2", lat = "13.3", long = "52.5")))
        val result = importer().run(partner, source)
        assertEquals(ImportStatus.OK, result.status, result.error)
        assertEquals(LocalDate.of(2026, 11, 1), result.replikatDate)
        assertContains(result.warnings.single(), "1 Datensätze")

        files.clear()
        provide(LocalDate.of(2026, 11, 2), false, listOf(location("1")), corruptMd5 = true)
        val corrupt = importer().run(partner, source)
        assertEquals(ImportStatus.FAILED, corrupt.status)
        assertContains(corrupt.error!!, "MD5")
        assertEquals(listOf("1", "2"), houses(), "a failed import changes nothing")

        files.clear()
        assertContains(importer().run(partner, source).error!!, "Kein Replikat")
    }
}
