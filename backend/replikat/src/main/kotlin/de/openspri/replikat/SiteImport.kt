package de.openspri.replikat

import de.openspri.domain.availability.AvailabilitySite
import de.openspri.domain.availability.AvailabilitySites
import de.openspri.domain.availability.ChangeMark
import de.openspri.domain.availability.Location
import de.openspri.domain.availability.OrderKind
import de.openspri.domain.availability.ProductAvailability
import de.openspri.domain.availability.TechnologyAvailability
import java.io.ByteArrayInputStream
import java.time.Clock

/** Result of an import of own locations. */
data class SiteImportResult(val added: Int, val changed: Int, val deleted: Int, val total: Int)

/**
 * Import of our own locations (D-13, AUFGABE 5.4) from a Replikat (XML) or a CSV file:
 * - `replace`: the file is the complete inventory; everything else is removed
 * - `merge`: the records of the file are added or replaced (same [de.openspri.domain.availability.LocationKey])
 * - a delta Replikat is applied by its change marks (ADD/MOD replace, DEL removes, UCH ignored)
 * Every record is checked against Replikat.xsd 1.7 before anything is changed.
 */
class SiteImport(private val sites: AvailabilitySites, private val clock: Clock = Clock.systemDefaultZone()) {

    enum class Mode { REPLACE, MERGE }

    fun importXml(content: ByteArray, mode: Mode): SiteImportResult {
        val xml = ReplikatFiles.xml(content).readAllBytes()
        val errors = ReplikatXml.validate(ByteArrayInputStream(xml))
        if (errors.isNotEmpty()) throw ReplikatInvalidException(errors)
        val records = mutableListOf<Location>()
        val header = ReplikatXml.read(ByteArrayInputStream(xml)) { records += it }
        if (header.delta) return applyDelta(records)
        return apply(records.map { it.copy(change = null) }, mode)
    }

    fun importCsv(text: String, mode: Mode): SiteImportResult = apply(SiteCsv.parse(text), mode)

    private fun apply(records: List<Location>, mode: Mode): SiteImportResult {
        val duplicates = records.groupBy { it.key }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Standorte mehrfach in der Datei: ${duplicates.take(5).joinToString { it.address }}" }
        val existing = sites.all().associateBy { it.location.key }
        if (mode == Mode.REPLACE) {
            val total = sites.replaceAll(records, clock.instant())
            val changed = records.count { existing[it.key]?.location?.let { old -> old != it } == true }
            return SiteImportResult(records.count { it.key !in existing }, changed, (existing.keys - records.map { it.key }.toSet()).size, total)
        }
        var added = 0
        var changed = 0
        records.forEach { location ->
            val old = existing[location.key]
            if (old == null) added++ else if (old.location != location) changed++
            sites.save(AvailabilitySite(old?.id, location, clock.instant()))
        }
        return SiteImportResult(added, changed, 0, sites.all().count())
    }

    private fun applyDelta(records: List<Location>): SiteImportResult {
        val existing = sites.all().associateBy { it.location.key }
        var added = 0
        var changed = 0
        var deleted = 0
        records.forEach { record ->
            val old = existing[record.key]
            when (record.change) {
                ChangeMark.DEL -> if (old != null && sites.delete(old.id!!)) deleted++
                ChangeMark.UCH -> Unit
                ChangeMark.ADD, ChangeMark.MOD, null -> {
                    if (old == null) added++ else changed++
                    sites.save(AvailabilitySite(old?.id, record.copy(change = null), clock.instant()))
                }
            }
        }
        return SiteImportResult(added, changed, deleted, sites.all().count())
    }
}

/**
 * CSV for own locations (D-13): `;`-separated with a header line, one line per location and product. Lines of the same
 * address (and HomeIDs) form one location; technology data is taken per `technologie`. Columns (names of the
 * Replikat elements): `aid;plz;o;ot;s;h;hz;gebaeudeteil;long;lat;produkt;technologie;downBB;upBB;bereitstellungszeit;
 * ausbaustatus;auftragsart;eigentuemerdaten;kont;hid` (HomeIDs separated by `|`). Only `plz`, `o`, `s`, `h` and
 * `produkt` are mandatory; unknown columns are an error.
 */
object SiteCsv {
    val COLUMNS = listOf(
        "aid", "plz", "o", "ot", "s", "h", "hz", "gebaeudeteil", "long", "lat", "produkt", "technologie", "downBB", "upBB",
        "bereitstellungszeit", "ausbaustatus", "auftragsart", "eigentuemerdaten", "kont", "hid",
    )

    fun parse(text: String): List<Location> {
        val lines = text.removePrefix("﻿").lines().withIndex().filter { it.value.isNotBlank() }
        require(lines.isNotEmpty()) { "CSV ist leer" }
        val header = lines.first().value.split(';').map { it.trim() }
        (header - COLUMNS.toSet()).takeIf { it.isNotEmpty() }?.let { throw IllegalArgumentException("Zeile 1: unbekannte Spalten $it; zulässig: ${COLUMNS.joinToString()}") }
        listOf("plz", "o", "s", "h", "produkt").filter { it !in header }.takeIf { it.isNotEmpty() }?.let { throw IllegalArgumentException("Zeile 1: Pflichtspalten fehlen: $it") }
        val errors = mutableListOf<String>()
        val rows = lines.drop(1).mapNotNull { (index, line) ->
            val cells = line.split(';').map { it.trim() }
            if (cells.size > header.size) { errors += "Zeile ${index + 1}: mehr Werte als Spalten"; return@mapNotNull null }
            val row = header.indices.associate { header[it] to cells.getOrNull(it).orEmpty().ifBlank { null } }
            index + 1 to row
        }
        val locations = rows.groupBy { (_, row) -> listOf("aid", "plz", "o", "ot", "s", "h", "hz", "gebaeudeteil", "hid").map { row[it]?.lowercase() } }.values.mapNotNull { group ->
            val (line, first) = group.first()
            try {
                val products = group.map { (_, row) ->
                    ProductAvailability(
                        product = row["produkt"] ?: throw IllegalArgumentException("produkt fehlt"),
                        technology = row["technologie"], downstream = row["downBB"], upstream = row["upBB"], provisioningDays = row["bereitstellungszeit"],
                    )
                }
                val homeIds = first["hid"]?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                val technologies = group.mapNotNull { (_, row) -> row["technologie"]?.let { it to row } }.distinctBy { it.first }.map { (technology, row) ->
                    TechnologyAvailability(
                        technology = technology,
                        quota = row["kont"],
                        homeIds = homeIds,
                        expansionStatus = row["ausbaustatus"],
                        orderKind = row["auftragsart"]?.let { value -> OrderKind.entries.firstOrNull { it.name == value.uppercase() } ?: throw IllegalArgumentException("auftragsart: STANDARD oder KONNEKTIVITAET") },
                        ownerDataRequired = row["eigentuemerdaten"]?.let { it.equals("true", true) || it.equals("ja", true) },
                    )
                }
                val location = Location(
                    addressId = first["aid"], longitude = first["long"], latitude = first["lat"],
                    postalCode = first["plz"] ?: throw IllegalArgumentException("plz fehlt"), city = first["o"] ?: throw IllegalArgumentException("o fehlt"),
                    district = first["ot"], street = first["s"] ?: throw IllegalArgumentException("s fehlt"), houseNumber = first["h"] ?: throw IllegalArgumentException("h fehlt"),
                    houseNumberSuffix = first["hz"], buildingPart = first["gebaeudeteil"], products = products, technologies = technologies,
                )
                ReplikatJson.validate(location).forEach { errors += "Zeile $line: $it" }
                location
            } catch (e: IllegalArgumentException) {
                errors += "Zeile $line: ${e.message}"
                null
            }
        }
        if (errors.isNotEmpty()) throw ReplikatInvalidException(errors.take(50))
        return locations
    }
}
