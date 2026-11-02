package de.openspri.domain.availability

import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerId
import java.time.Instant
import java.time.LocalDate

/** How we learn the availability of a partner (Auftraggeber, AUFGABE 5.4). */
enum class SourceKind {
    /** daily download and import of the partner's Replikat */
    REPLIKAT,
    /** online query against an instance of this software (`GET /api/v1/availability`) */
    ONLINE,
}

/**
 * Where and how we get the availability of a partner.
 *
 * @property url REPLIKAT: base URL of the download server (`…/replikat/`), ONLINE: base URL of the instance
 * @property username Basic-Auth user; without it the secret is sent as Bearer token
 * @property secret password or token; stored encrypted (master key), never returned by the API
 * @property delta REPLIKAT: apply the partner's delta Replikat if the previous day was imported
 */
data class AvailabilitySource(
    val id: Long?,
    val partnerId: PartnerId,
    val kind: SourceKind,
    val url: String,
    val username: String? = null,
    val secret: String? = null,
    val delta: Boolean = false,
    val enabled: Boolean = true,
)

interface AvailabilitySources {
    fun list(): List<AvailabilitySource>
    fun get(id: Long): AvailabilitySource?
    fun save(source: AvailabilitySource): AvailabilitySource
    fun delete(id: Long): Boolean
}

enum class ImportStatus { OK, FAILED }

/** One import of a partner's Replikat (`replikat_import`). */
data class ReplikatImport(
    val id: Long?,
    val sourceId: Long,
    val partnerId: PartnerId,
    val startedAt: Instant,
    val finishedAt: Instant,
    val status: ImportStatus,
    val fileName: String? = null,
    val replikatDate: LocalDate? = null,
    val delta: Boolean = false,
    val records: Int = 0,
    val added: Int = 0,
    val changed: Int = 0,
    val deleted: Int = 0,
    /** e.g. coordinates that look swapped (SA-017) */
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

interface ReplikatImports {
    fun record(entry: ReplikatImport): ReplikatImport
    fun list(sourceId: Long, limit: Int): List<ReplikatImport>
    fun last(sourceId: Long): ReplikatImport?
}

/** The imported locations of the partners (`partner_availability`). */
interface PartnerLocations {
    /** Replaces all locations of the partner (full Replikat). */
    fun replaceAll(partnerId: PartnerId, locations: List<Location>)

    /** Applies a delta Replikat; returns added, changed and deleted. */
    fun applyDelta(partnerId: PartnerId, records: List<Location>): Triple<Int, Int, Int>

    fun search(partnerId: PartnerId, query: AvailabilityQuery): List<Location>
    fun count(partnerId: PartnerId): Int
}

/**
 * A search across partners (AUFGABE 5.4): address fields (`s` and `o` as prefix) or coordinates with a radius, or
 * HomeID or Adress-ID.
 */
data class AvailabilityQuery(
    val address: AddressFilter = AddressFilter(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int = 50,
) {
    val geo: Boolean get() = latitude != null && longitude != null
    val empty: Boolean get() = address.empty && !geo
}

/**
 * Adapter for the availability of one partner (AUFGABE 5.4, `AvailabilityProvider`-SPI). v1 provides "Replikat (lokal)"
 * and "Online-Abfrage gegen eine Instanz dieser Software"; adapters for other partner APIs can be added as plugin.
 */
interface AvailabilityProvider {
    val kind: SourceKind
    fun search(partner: Partner, source: AvailabilitySource, query: AvailabilityQuery): List<Location>
}

/** A hit of the search: the partner and its record. */
data class AvailabilityHit(val partnerId: PartnerId, val partnerName: String, val kind: SourceKind, val location: Location)

/** Searches the availability at all partners with an enabled source; a failing partner is reported, not fatal. */
class AvailabilitySearch(
    private val sources: AvailabilitySources,
    private val partners: (PartnerId) -> Partner?,
    private val providers: List<AvailabilityProvider>,
) {
    data class Result(val hits: List<AvailabilityHit>, val errors: Map<PartnerId, String>)

    fun search(query: AvailabilityQuery, partnerId: PartnerId? = null): Result {
        require(!query.empty) { "Mindestens eine Angabe: Adresse, Koordinaten, HomeID oder Adress-ID" }
        val hits = mutableListOf<AvailabilityHit>()
        val errors = mutableMapOf<PartnerId, String>()
        sources.list().filter { it.enabled && (partnerId == null || it.partnerId == partnerId) }.forEach { source ->
            val partner = partners(source.partnerId)?.takeIf { it.enabled } ?: return@forEach
            val provider = providers.firstOrNull { it.kind == source.kind } ?: return@forEach
            try {
                provider.search(partner, source, query).forEach { hits += AvailabilityHit(partner.id, partner.name, source.kind, it) }
            } catch (e: Exception) {
                errors[partner.id] = e.message ?: e.javaClass.simpleName
            }
        }
        return Result(hits, errors)
    }
}
