package de.openspri.persistence

import de.openspri.domain.availability.AddressFilter
import de.openspri.domain.availability.AvailabilitySite
import de.openspri.domain.availability.AvailabilitySites
import de.openspri.domain.availability.Location
import de.openspri.domain.availability.LocationKey
import de.openspri.domain.availability.ReplikatExportState
import de.openspri.domain.availability.ReplikatKind
import de.openspri.domain.availability.ReplikatRelease
import de.openspri.domain.availability.ReplikatReleases
import de.openspri.domain.availability.ReplikatSnapshot
import de.openspri.domain.availability.ReplikatSnapshots
import de.openspri.domain.partner.PartnerId
import de.openspri.replikat.ReplikatJson
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat

/** [LocationKey] as SHA-256 (fixed length for the unique index). */
internal fun LocationKey.hash(): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("$address\u0000$buildingPart\u0000$homeIds".toByteArray()))

/** LIKE pattern for a prefix search with the wildcards of [text] escaped. */
internal fun prefixPattern(text: String) = text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

@Repository
class JdbcAvailabilitySites(private val jdbc: JdbcClient) : AvailabilitySites {

    override fun search(filter: AddressFilter): List<AvailabilitySite> {
        val where = mutableListOf("tenant_id = :tenant")
        val params = mutableMapOf<String, Any?>("tenant" to Tenant.DEFAULT, "limit" to filter.limit.coerceIn(1, 500))
        filter.postalCode?.takeIf { it.isNotBlank() }?.let { where += "postal_code = :plz"; params["plz"] = it.trim() }
        filter.city?.takeIf { it.isNotBlank() }?.let { where += "city LIKE :city"; params["city"] = prefixPattern(it) }
        filter.street?.takeIf { it.isNotBlank() }?.let { where += "street LIKE :street"; params["street"] = prefixPattern(it) }
        filter.houseNumber?.takeIf { it.isNotBlank() }?.let { where += "house_number = :h"; params["h"] = it.trim() }
        filter.houseNumberSuffix?.takeIf { it.isNotBlank() }?.let { where += "house_number_suffix = :hz"; params["hz"] = it.trim() }
        filter.addressId?.takeIf { it.isNotBlank() }?.let { where += "address_id = :aid"; params["aid"] = it.trim() }
        filter.homeId?.takeIf { it.isNotBlank() }?.let { where += "FIND_IN_SET(:hid, home_ids) > 0"; params["hid"] = it.trim() }
        filter.beforeId?.let { where += "id < :before"; params["before"] = it }
        return jdbc.sql("SELECT * FROM availability_site WHERE ${where.joinToString(" AND ")} ORDER BY id DESC LIMIT :limit")
            .params(params).query { rs, _ -> map(rs) }.list()
    }

    override fun get(id: Long): AvailabilitySite? =
        jdbc.sql("SELECT * FROM availability_site WHERE id = :id AND tenant_id = :tenant").param("id", id).param("tenant", Tenant.DEFAULT)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun save(site: AvailabilitySite): AvailabilitySite {
        val l = site.location.copy(change = null)
        val params = mapOf(
            "tenant" to Tenant.DEFAULT, "key" to l.key.hash(), "location" to ReplikatJson.write(l), "plz" to l.postalCode, "city" to l.city,
            "street" to l.street, "h" to l.houseNumber, "hz" to l.houseNumberSuffix, "aid" to l.addressId,
            "hids" to l.homeIds.sorted().joinToString(",").take(2000).ifEmpty { null }, "at" to Timestamp.from(site.updatedAt),
        )
        try {
            if (site.id == null) {
                val keys = GeneratedKeyHolder()
                jdbc.sql(
                    """INSERT INTO availability_site (tenant_id, location_key, location, postal_code, city, street, house_number, house_number_suffix,
                           address_id, home_ids, updated_at) VALUES (:tenant, :key, :location, :plz, :city, :street, :h, :hz, :aid, :hids, :at)""",
                ).params(params).update(keys, "id")
                return site.copy(id = keys.key!!.toLong(), location = l)
            }
            val updated = jdbc.sql(
                """UPDATE availability_site SET location_key = :key, location = :location, postal_code = :plz, city = :city, street = :street,
                       house_number = :h, house_number_suffix = :hz, address_id = :aid, home_ids = :hids, updated_at = :at
                   WHERE id = :id AND tenant_id = :tenant""",
            ).params(params + ("id" to site.id)).update()
            if (updated != 1) throw NoSuchElementException("Standort ${site.id} existiert nicht")
            return site.copy(location = l)
        } catch (e: DuplicateKeyException) {
            throw IllegalStateException("Standort existiert bereits (gleiche Adresse bzw. Adress-ID, Gebäudeteil und HomeIDs)")
        }
    }

    override fun delete(id: Long) =
        jdbc.sql("DELETE FROM availability_site WHERE id = :id AND tenant_id = :tenant").param("id", id).param("tenant", Tenant.DEFAULT).update() == 1

    override fun all(): Sequence<AvailabilitySite> = sequence {
        // in pages, so that a large inventory of sites does not have to fit into memory at once
        var after = 0L
        while (true) {
            val page = jdbc.sql("SELECT * FROM availability_site WHERE tenant_id = :tenant AND id > :after ORDER BY id LIMIT 1000")
                .param("tenant", Tenant.DEFAULT).param("after", after).query { rs, _ -> map(rs) }.list()
            if (page.isEmpty()) break
            yieldAll(page)
            after = page.last().id!!
        }
    }

    @Transactional
    override fun replaceAll(locations: List<Location>, at: Instant): Int {
        jdbc.sql("DELETE FROM availability_site WHERE tenant_id = :tenant").param("tenant", Tenant.DEFAULT).update()
        locations.forEach { save(AvailabilitySite(null, it, at)) }
        return locations.size
    }

    private fun map(rs: ResultSet) = AvailabilitySite(rs.getLong("id"), ReplikatJson.read(rs.getString("location")), rs.getTimestamp("updated_at").toInstant())
}

@Repository
class JdbcReplikatReleases(private val jdbc: JdbcClient) : ReplikatReleases {
    override fun list(): List<ReplikatRelease> = jdbc.sql("SELECT * FROM replikat_release ORDER BY partner_id").query { rs, _ -> map(rs) }.list()

    override fun get(partnerId: PartnerId): ReplikatRelease? =
        jdbc.sql("SELECT * FROM replikat_release WHERE partner_id = :p").param("p", partnerId.value).query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun save(release: ReplikatRelease) {
        jdbc.sql(
            """INSERT INTO replikat_release (partner_id, enabled, delta, omitted_fields, quotas) VALUES (:p, :e, :d, :o, :q)
               ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), delta = VALUES(delta), omitted_fields = VALUES(omitted_fields), quotas = VALUES(quotas)""",
        ).param("p", release.partnerId.value).param("e", release.enabled).param("d", release.delta)
            .param("o", release.omittedFields.sorted().joinToString(",").ifEmpty { null }).param("q", release.quotas.sorted().joinToString(",").ifEmpty { null })
            .update()
    }

    override fun delete(partnerId: PartnerId) = jdbc.sql("DELETE FROM replikat_release WHERE partner_id = :p").param("p", partnerId.value).update() == 1

    private fun map(rs: ResultSet) = ReplikatRelease(
        PartnerId(rs.getLong("partner_id")), rs.getBoolean("enabled"), rs.getBoolean("delta"),
        rs.getString("omitted_fields")?.split(',')?.toSet().orEmpty(), rs.getString("quotas")?.split(',')?.toSet().orEmpty(),
    )
}

@Repository
class JdbcReplikatSnapshots(private val jdbc: JdbcClient) : ReplikatSnapshots {

    @Transactional
    override fun save(snapshot: ReplikatSnapshot, content: ByteArray, meta: ByteArray): ReplikatSnapshot {
        jdbc.sql("DELETE FROM replikat_snapshot WHERE partner_id = :p AND file_name = :f").param("p", snapshot.partnerId.value).param("f", snapshot.fileName).update()
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """INSERT INTO replikat_snapshot (partner_id, replikat_date, kind, file_name, meta_file_name, md5, records, size, content, meta, created_at)
               VALUES (:p, :d, :k, :f, :m, :md5, :r, :s, :c, :meta, :at)""",
        ).param("p", snapshot.partnerId.value).param("d", snapshot.date).param("k", snapshot.kind.name).param("f", snapshot.fileName)
            .param("m", snapshot.metaFileName).param("md5", snapshot.md5).param("r", snapshot.records).param("s", snapshot.size)
            .param("c", content).param("meta", meta).param("at", Timestamp.from(snapshot.createdAt))
            .update(keys, "id")
        return snapshot.copy(id = keys.key!!.toLong())
    }

    override fun list(partnerId: PartnerId): List<ReplikatSnapshot> =
        jdbc.sql("SELECT id, partner_id, replikat_date, kind, file_name, meta_file_name, md5, records, size, created_at FROM replikat_snapshot WHERE partner_id = :p ORDER BY id DESC")
            .param("p", partnerId.value).query { rs, _ ->
                ReplikatSnapshot(
                    rs.getLong("id"), PartnerId(rs.getLong("partner_id")), rs.getDate("replikat_date").toLocalDate(), ReplikatKind.valueOf(rs.getString("kind")),
                    rs.getString("file_name"), rs.getString("meta_file_name"), rs.getString("md5"), rs.getInt("records"), rs.getLong("size"),
                    rs.getTimestamp("created_at").toInstant(),
                )
            }.list()

    override fun file(partnerId: PartnerId, fileName: String): ByteArray? =
        jdbc.sql(
            """SELECT CASE WHEN file_name = :f THEN content ELSE meta END FROM replikat_snapshot
               WHERE partner_id = :p AND (file_name = :f OR meta_file_name = :f) ORDER BY id DESC LIMIT 1""",
        ).param("p", partnerId.value).param("f", fileName).query { rs, _ -> rs.getBytes(1) }.optional().orElse(null)

    override fun purgeBefore(date: LocalDate): Int = jdbc.sql("DELETE FROM replikat_snapshot WHERE replikat_date < :d").param("d", date).update()
}

@Repository
class JdbcReplikatExportState(private val jdbc: JdbcClient) : ReplikatExportState {
    override fun load(partnerId: PartnerId): Map<LocationKey, Location> =
        jdbc.sql("SELECT location FROM replikat_export_state WHERE partner_id = :p").param("p", partnerId.value)
            .query { rs, _ -> ReplikatJson.read(rs.getString(1)) }.list().associateBy { it.key }

    @Transactional
    override fun replace(partnerId: PartnerId, state: Map<LocationKey, Location>) {
        jdbc.sql("DELETE FROM replikat_export_state WHERE partner_id = :p").param("p", partnerId.value).update()
        state.forEach { (key, location) ->
            jdbc.sql("INSERT INTO replikat_export_state (partner_id, location_key, location) VALUES (:p, :k, :l)")
                .param("p", partnerId.value).param("k", key.hash()).param("l", ReplikatJson.write(location)).update()
        }
    }
}
