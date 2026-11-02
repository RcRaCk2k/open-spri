package de.openspri.replikat

import de.openspri.domain.InMemoryAvailability
import de.openspri.domain.availability.AvailabilitySite
import de.openspri.domain.availability.ChangeMark
import de.openspri.domain.availability.Location
import de.openspri.domain.availability.ProductAvailability
import de.openspri.domain.availability.ReplikatKind
import de.openspri.domain.availability.ReplikatRelease
import de.openspri.domain.availability.TechnologyAvailability
import de.openspri.domain.partner.PartnerId
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReplikatExporterTest {
    private val store = InMemoryAvailability()
    private val partner = PartnerId(4)
    private var now = Instant.parse("2026-11-02T02:00:00Z")
    private val clock get() = Clock.fixed(now, ZoneId.of("Europe/Berlin"))

    private fun exporter() = ReplikatExporter(store, store, store, store, { "TSTNZ" }, clock)

    private fun site(house: String, product: String = "FTTH 100", quota: String = "Nord") = Location(
        addressId = "AID-$house", longitude = "13.3", latitude = "52.5", postalCode = "12345", city = "Musterstadt", street = "Musterstraße",
        houseNumber = house, products = listOf(ProductAvailability(product, "FTTH")), technologies = listOf(TechnologyAvailability("FTTH", quota = quota)),
    )

    private fun records(kind: ReplikatKind): Pair<ReplikatHeader, List<Location>> {
        val snapshot = store.list(partner).first { it.kind == kind && it.date == java.time.LocalDate.now(clock) }
        val records = mutableListOf<Location>()
        val header = ReplikatXml.read(ReplikatFiles.xml(store.file(partner, snapshot.fileName)!!)) { records += it }
        assertEquals(snapshot.md5, ReplikatMeta.parse(store.file(partner, snapshot.metaFileName)!!).md5)
        return header to records
    }

    @Test
    fun `full and delta Replikat with change marks relative to the previous one`() {
        store.save(ReplikatRelease(partner))
        store.save(AvailabilitySite(null, site("1"), now))
        store.save(AvailabilitySite(null, site("2"), now))
        exporter().exportAll()
        assertEquals(listOf(ChangeMark.ADD, ChangeMark.ADD), records(ReplikatKind.FULL).second.map { it.change })
        assertEquals(2, records(ReplikatKind.DELTA).second.size)

        // next day: house 1 changed, house 2 removed, house 3 new
        now = now.plusSeconds(86_400)
        val one = store.sites.first { it.location.houseNumber == "1" }
        store.save(one.copy(location = site("1", product = "FTTH 250")))
        store.delete(store.sites.first { it.location.houseNumber == "2" }.id!!)
        store.save(AvailabilitySite(null, site("3"), now))
        exporter().exportAll()

        val (header, full) = records(ReplikatKind.FULL)
        assertEquals(false, header.delta)
        assertEquals(mapOf("1" to ChangeMark.MOD, "3" to ChangeMark.ADD), full.associate { it.houseNumber to it.change })
        val (deltaHeader, delta) = records(ReplikatKind.DELTA)
        assertEquals(true, deltaHeader.delta)
        assertEquals(mapOf("1" to ChangeMark.MOD, "2" to ChangeMark.DEL, "3" to ChangeMark.ADD), delta.associate { it.houseNumber to it.change })
        assertEquals("TSTNZ_20261103_delta.zip", store.list(partner).first { it.kind == ReplikatKind.DELTA }.fileName)

        // unchanged on the third day: UCH in the full Replikat, empty delta
        now = now.plusSeconds(86_400)
        exporter().exportAll()
        assertEquals(setOf(ChangeMark.UCH), records(ReplikatKind.FULL).second.map { it.change }.toSet())
        assertEquals(0, records(ReplikatKind.DELTA).second.size)
    }

    @Test
    fun `release limits fields and quotas, old files are removed after 31 days`() {
        store.save(ReplikatRelease(partner, delta = false, omittedFields = setOf("geo", "kont"), quotas = setOf("Nord")))
        store.save(AvailabilitySite(null, site("1"), now))
        store.save(AvailabilitySite(null, site("2", quota = "Süd"), now))
        exporter().exportAll()
        val full = records(ReplikatKind.FULL).second.single()
        assertNull(full.longitude)
        assertNull(full.technologies.single().quota)
        assertNull(store.list(partner).firstOrNull { it.kind == ReplikatKind.DELTA })

        now = now.plusSeconds(86_400L * 32)
        exporter().exportAll()
        assertEquals(1, store.list(partner).size, "only today's Replikat, the one of 32 days ago is removed")
        assertNotNull(store.list(partner).single())
    }
}
