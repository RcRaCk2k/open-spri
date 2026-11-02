package de.openspri.domain.availability

import de.openspri.domain.partner.PartnerId
import java.time.Instant
import java.time.LocalDate

/** Search by address (AUFGABE 5.4): every field narrows the result; street and city as prefix, case-insensitive. */
data class AddressFilter(
    val postalCode: String? = null,
    val city: String? = null,
    val street: String? = null,
    val houseNumber: String? = null,
    val houseNumberSuffix: String? = null,
    val homeId: String? = null,
    val addressId: String? = null,
    val beforeId: Long? = null,
    val limit: Int = 50,
) {
    val empty: Boolean get() = listOf(postalCode, city, street, houseNumber, houseNumberSuffix, homeId, addressId).all { it.isNullOrBlank() }
}

/** One of our own locations (Leistungserbringer, D-13): maintained via REST or import, source of our Replikat. */
data class AvailabilitySite(val id: Long?, val location: Location, val updatedAt: Instant)

interface AvailabilitySites {
    fun search(filter: AddressFilter): List<AvailabilitySite>
    fun get(id: Long): AvailabilitySite?

    /** Inserts or replaces; a second site with the same [LocationKey] is rejected (IllegalStateException). */
    fun save(site: AvailabilitySite): AvailabilitySite
    fun delete(id: Long): Boolean

    /** All sites, in a stable order (for the Replikat). */
    fun all(): Sequence<AvailabilitySite>

    /** Replaces all sites (import of a full Replikat); returns the number of sites. */
    fun replaceAll(locations: List<Location>, at: Instant): Int
}

/**
 * Release of our Replikat for a partner (AUFGABE 5.4, bilateral agreement).
 *
 * @property delta also provide a delta Replikat (optional per Replikat 3.1)
 * @property omittedFields optional Replikat elements not agreed with this partner (e.g. `geo`, `kont`)
 * @property quotas only locations with one of these `kont` values (Kontingentzuordnung); empty = all
 */
data class ReplikatRelease(
    val partnerId: PartnerId,
    val enabled: Boolean = true,
    val delta: Boolean = true,
    val omittedFields: Set<String> = emptySet(),
    val quotas: Set<String> = emptySet(),
)

interface ReplikatReleases {
    fun list(): List<ReplikatRelease>
    fun get(partnerId: PartnerId): ReplikatRelease?
    fun save(release: ReplikatRelease)
    fun delete(partnerId: PartnerId): Boolean
}

enum class ReplikatKind { FULL, DELTA }

/** A Replikat provided for a partner: the ZIP and its meta file. */
data class ReplikatSnapshot(
    val id: Long?,
    val partnerId: PartnerId,
    val date: LocalDate,
    val kind: ReplikatKind,
    val fileName: String,
    val metaFileName: String,
    val md5: String,
    val records: Int,
    val size: Long,
    val createdAt: Instant,
)

interface ReplikatSnapshots {
    /** Stores (or replaces the file of the same name) the Replikat and its meta file. */
    fun save(snapshot: ReplikatSnapshot, content: ByteArray, meta: ByteArray): ReplikatSnapshot
    fun list(partnerId: PartnerId): List<ReplikatSnapshot>

    /** Content of a file of the partner by name: the ZIP or the meta file. */
    fun file(partnerId: PartnerId, fileName: String): ByteArray?

    /** Removes Replikate older than [date]; the specification requires the last 31 days (Replikat 3.1). */
    fun purgeBefore(date: LocalDate): Int
}

/**
 * What a partner received with its last Replikat, per record: the basis of the change marks and the delta
 * ("Das Delta bezieht sich auf das zuletzt erzeugte (Delta-)Replikat", Replikat 3.1). [Location] is kept to be
 * able to send DEL with the address.
 */
interface ReplikatExportState {
    fun load(partnerId: PartnerId): Map<LocationKey, Location>
    fun replace(partnerId: PartnerId, state: Map<LocationKey, Location>)
}
