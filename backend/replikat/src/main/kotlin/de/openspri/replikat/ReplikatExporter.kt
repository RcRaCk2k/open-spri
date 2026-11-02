package de.openspri.replikat

import de.openspri.domain.availability.AvailabilitySites
import de.openspri.domain.availability.ChangeMark
import de.openspri.domain.availability.Location
import de.openspri.domain.availability.LocationKey
import de.openspri.domain.availability.ReplikatExportState
import de.openspri.domain.availability.ReplikatKind
import de.openspri.domain.availability.ReplikatRelease
import de.openspri.domain.availability.ReplikatReleases
import de.openspri.domain.availability.ReplikatSnapshot
import de.openspri.domain.availability.ReplikatSnapshots
import de.openspri.domain.partner.PartnerId
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate

/** Result of the export for one partner. */
data class ExportResult(val partnerId: PartnerId, val full: ReplikatSnapshot?, val delta: ReplikatSnapshot?, val error: String? = null)

/**
 * Daily Replikat of our own locations for every released partner (AUFGABE 5.4, Replikat 3.1):
 * - **full Replikat** with all locations of the release (fields and quotas as agreed). Every record carries a change
 *   mark relative to the previous Replikat of the partner (ADD, MOD, UCH), as the specification recommends.
 * - **delta Replikat** (if agreed) with ADD, MOD and DEL relative to the previous one; DEL carries the last known data.
 * - both as ZIP with meta file; files older than [retentionDays] are removed (31 days required).
 */
class ReplikatExporter(
    private val sites: AvailabilitySites,
    private val releases: ReplikatReleases,
    private val snapshots: ReplikatSnapshots,
    private val state: ReplikatExportState,
    private val carrierCode: () -> String,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val retentionDays: Long = 31,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun exportAll(): List<ExportResult> {
        val results = releases.list().filter { it.enabled }.map { release ->
            try {
                export(release)
            } catch (e: Exception) {
                log.error("Replikat for partner {} failed", release.partnerId.value, e)
                ExportResult(release.partnerId, null, null, e.message ?: e.javaClass.simpleName)
            }
        }
        snapshots.purgeBefore(LocalDate.now(clock).minusDays(retentionDays))
        return results
    }

    fun export(release: ReplikatRelease): ExportResult {
        val today = LocalDate.now(clock)
        val now = clock.instant()
        val current = sites.all().map { it.location.copy(change = null) }
            .filter { location -> release.quotas.isEmpty() || location.technologies.any { it.quota in release.quotas } }
            .map { ReplikatJson.without(it, release.omittedFields) }
            .associateBy { it.key }
        val previous = state.load(release.partnerId)

        // the state is stored without change marks, like [current]
        fun mark(key: LocationKey, location: Location) = when (previous[key]) {
            null -> ChangeMark.ADD
            location -> ChangeMark.UCH
            else -> ChangeMark.MOD
        }

        val full = current.map { (key, location) -> location.copy(change = mark(key, location)) }
        val fullSnapshot = store(release.partnerId, today, ReplikatKind.FULL, full)
        val deltaSnapshot = if (release.delta) {
            val changed = full.filter { it.change != ChangeMark.UCH }
            val deleted = (previous.keys - current.keys).map { previous.getValue(it).copy(change = ChangeMark.DEL) }
            store(release.partnerId, today, ReplikatKind.DELTA, changed + deleted)
        } else {
            null
        }
        state.replace(release.partnerId, current)
        log.info("Replikat for partner {}: {} records, delta {}", release.partnerId.value, full.size, deltaSnapshot?.records)
        return ExportResult(release.partnerId, fullSnapshot, deltaSnapshot)
    }

    private fun store(partnerId: PartnerId, date: LocalDate, kind: ReplikatKind, records: List<Location>): ReplikatSnapshot {
        val header = ReplikatHeader(date, carrierCode(), delta = kind == ReplikatKind.DELTA)
        val pkg = ReplikatFiles.pack(header, records.asSequence(), clock.instant(), clock.zone)
        return snapshots.save(
            ReplikatSnapshot(null, partnerId, date, kind, pkg.fileName, pkg.metaFileName, pkg.meta.md5, records.size, pkg.content.size.toLong(), clock.instant()),
            pkg.content,
            pkg.meta.toXml(),
        )
    }
}
