package de.openspri.persistence

import de.openspri.domain.availability.AvailabilityQuery
import de.openspri.domain.availability.AvailabilitySource
import de.openspri.domain.availability.AvailabilitySources
import de.openspri.domain.availability.ChangeMark
import de.openspri.domain.availability.ImportStatus
import de.openspri.domain.availability.Location
import de.openspri.domain.availability.PartnerLocations
import de.openspri.domain.availability.ReplikatImport
import de.openspri.domain.availability.ReplikatImports
import de.openspri.domain.availability.SourceKind
import de.openspri.domain.partner.PartnerId
import de.openspri.replikat.ReplikatJson
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import kotlin.math.cos

@Repository
class JdbcAvailabilitySources(private val jdbc: JdbcClient) : AvailabilitySources {
    override fun list(): List<AvailabilitySource> =
        jdbc.sql("SELECT * FROM availability_source WHERE tenant_id = :t ORDER BY id").param("t", Tenant.DEFAULT).query { rs, _ -> map(rs) }.list()

    override fun get(id: Long): AvailabilitySource? =
        jdbc.sql("SELECT * FROM availability_source WHERE id = :id AND tenant_id = :t").param("id", id).param("t", Tenant.DEFAULT)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun save(source: AvailabilitySource): AvailabilitySource {
        val params = mapOf(
            "t" to Tenant.DEFAULT, "p" to source.partnerId.value, "k" to source.kind.name, "u" to source.url, "user" to source.username,
            "s" to source.secret, "d" to source.delta, "e" to source.enabled,
        )
        if (source.id == null) {
            val keys = GeneratedKeyHolder()
            jdbc.sql("INSERT INTO availability_source (tenant_id, partner_id, kind, url, username, secret, delta, enabled) VALUES (:t, :p, :k, :u, :user, :s, :d, :e)")
                .params(params).update(keys, "id")
            return source.copy(id = keys.key!!.toLong())
        }
        val updated = jdbc.sql(
            "UPDATE availability_source SET partner_id = :p, kind = :k, url = :u, username = :user, secret = :s, delta = :d, enabled = :e WHERE id = :id AND tenant_id = :t",
        ).params(params + ("id" to source.id)).update()
        if (updated != 1) throw NoSuchElementException("Quelle ${source.id} existiert nicht")
        return source
    }

    override fun delete(id: Long) = jdbc.sql("DELETE FROM availability_source WHERE id = :id AND tenant_id = :t").param("id", id).param("t", Tenant.DEFAULT).update() == 1

    private fun map(rs: ResultSet) = AvailabilitySource(
        rs.getLong("id"), PartnerId(rs.getLong("partner_id")), SourceKind.valueOf(rs.getString("kind")), rs.getString("url"),
        rs.getString("username"), rs.getString("secret"), rs.getBoolean("delta"), rs.getBoolean("enabled"),
    )
}

@Repository
class JdbcReplikatImports(private val jdbc: JdbcClient) : ReplikatImports {
    override fun record(entry: ReplikatImport): ReplikatImport {
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """INSERT INTO replikat_import (source_id, partner_id, started_at, finished_at, status, file_name, replikat_date, delta, records, added, changed, deleted, warnings, error)
               VALUES (:s, :p, :start, :end, :status, :f, :d, :delta, :r, :a, :c, :del, :w, :e)""",
        ).param("s", entry.sourceId).param("p", entry.partnerId.value).param("start", Timestamp.from(entry.startedAt)).param("end", Timestamp.from(entry.finishedAt))
            .param("status", entry.status.name).param("f", entry.fileName).param("d", entry.replikatDate).param("delta", entry.delta).param("r", entry.records)
            .param("a", entry.added).param("c", entry.changed).param("del", entry.deleted)
            .param("w", entry.warnings.joinToString("\n").take(4000).ifEmpty { null }).param("e", entry.error?.take(4000))
            .update(keys, "id")
        return entry.copy(id = keys.key!!.toLong())
    }

    override fun list(sourceId: Long, limit: Int): List<ReplikatImport> =
        jdbc.sql("SELECT * FROM replikat_import WHERE source_id = :s ORDER BY id DESC LIMIT :l").param("s", sourceId).param("l", limit.coerceIn(1, 500))
            .query { rs, _ -> map(rs) }.list()

    override fun last(sourceId: Long): ReplikatImport? = list(sourceId, 1).firstOrNull()

    private fun map(rs: ResultSet) = ReplikatImport(
        rs.getLong("id"), rs.getLong("source_id"), PartnerId(rs.getLong("partner_id")), rs.getTimestamp("started_at").toInstant(),
        rs.getTimestamp("finished_at").toInstant(), ImportStatus.valueOf(rs.getString("status")), rs.getString("file_name"),
        rs.getDate("replikat_date")?.toLocalDate(), rs.getBoolean("delta"), rs.getInt("records"), rs.getInt("added"), rs.getInt("changed"),
        rs.getInt("deleted"), rs.getString("warnings")?.split('\n').orEmpty(), rs.getString("error"),
    )
}

@Repository
class JdbcPartnerLocations(private val jdbc: JdbcClient) : PartnerLocations {

    @Transactional
    override fun replaceAll(partnerId: PartnerId, locations: List<Location>) {
        jdbc.sql("DELETE FROM partner_location WHERE partner_id = :p").param("p", partnerId.value).update()
        locations.forEach { upsert(partnerId, it) }
    }

    @Transactional
    override fun applyDelta(partnerId: PartnerId, records: List<Location>): Triple<Int, Int, Int> {
        var added = 0
        var changed = 0
        var deleted = 0
        records.forEach { record ->
            val exists = jdbc.sql("SELECT COUNT(*) FROM partner_location WHERE partner_id = :p AND location_key = :k")
                .param("p", partnerId.value).param("k", record.key.hash()).query(Int::class.java).single() > 0
            when (record.change) {
                ChangeMark.DEL -> if (exists) {
                    jdbc.sql("DELETE FROM partner_location WHERE partner_id = :p AND location_key = :k").param("p", partnerId.value).param("k", record.key.hash()).update()
                    deleted++
                }
                ChangeMark.UCH -> Unit
                ChangeMark.ADD, ChangeMark.MOD, null -> {
                    if (exists) changed++ else added++
                    upsert(partnerId, record)
                }
            }
        }
        return Triple(added, changed, deleted)
    }

    override fun search(partnerId: PartnerId, query: AvailabilityQuery): List<Location> {
        val a = query.address
        val where = mutableListOf("partner_id = :p")
        val params = mutableMapOf<String, Any?>("p" to partnerId.value, "limit" to a.limit.coerceIn(1, 500))
        a.postalCode?.takeIf { it.isNotBlank() }?.let { where += "postal_code = :plz"; params["plz"] = it.trim() }
        a.city?.takeIf { it.isNotBlank() }?.let { where += "city LIKE :city"; params["city"] = prefixPattern(it) }
        a.street?.takeIf { it.isNotBlank() }?.let { where += "street LIKE :street"; params["street"] = prefixPattern(it) }
        a.houseNumber?.takeIf { it.isNotBlank() }?.let { where += "house_number = :h"; params["h"] = it.trim() }
        a.houseNumberSuffix?.takeIf { it.isNotBlank() }?.let { where += "house_number_suffix = :hz"; params["hz"] = it.trim() }
        a.addressId?.takeIf { it.isNotBlank() }?.let { where += "address_id = :aid"; params["aid"] = it.trim() }
        a.homeId?.takeIf { it.isNotBlank() }?.let { where += "FIND_IN_SET(:hid, home_ids) > 0"; params["hid"] = it.trim() }
        if (query.geo) {
            // bounding box around the point; good enough for radii up to a few kilometres
            val dLat = query.radiusMeters / 111_320.0
            val dLong = query.radiusMeters / (111_320.0 * cos(Math.toRadians(query.latitude!!)))
            where += "latitude BETWEEN :lat1 AND :lat2 AND longitude BETWEEN :long1 AND :long2"
            params += mapOf("lat1" to query.latitude!! - dLat, "lat2" to query.latitude!! + dLat, "long1" to query.longitude!! - dLong, "long2" to query.longitude!! + dLong)
        }
        return jdbc.sql("SELECT location FROM partner_location WHERE ${where.joinToString(" AND ")} ORDER BY postal_code, street, house_number LIMIT :limit")
            .params(params).query { rs, _ -> ReplikatJson.read(rs.getString(1)) }.list()
    }

    override fun count(partnerId: PartnerId): Int =
        jdbc.sql("SELECT COUNT(*) FROM partner_location WHERE partner_id = :p").param("p", partnerId.value).query(Int::class.java).single()

    private fun upsert(partnerId: PartnerId, record: Location) {
        val l = record.copy(change = null)
        jdbc.sql(
            """INSERT INTO partner_location (partner_id, location_key, location, postal_code, city, street, house_number, house_number_suffix, address_id, home_ids, latitude, longitude)
               VALUES (:p, :k, :l, :plz, :city, :street, :h, :hz, :aid, :hids, :lat, :long)
               ON DUPLICATE KEY UPDATE location = VALUES(location), postal_code = VALUES(postal_code), city = VALUES(city), street = VALUES(street),
                   house_number = VALUES(house_number), house_number_suffix = VALUES(house_number_suffix), address_id = VALUES(address_id),
                   home_ids = VALUES(home_ids), latitude = VALUES(latitude), longitude = VALUES(longitude)""",
        ).param("p", partnerId.value).param("k", l.key.hash()).param("l", ReplikatJson.write(l)).param("plz", l.postalCode).param("city", l.city)
            .param("street", l.street).param("h", l.houseNumber).param("hz", l.houseNumberSuffix).param("aid", l.addressId)
            .param("hids", l.homeIds.sorted().joinToString(",").take(2000).ifEmpty { null })
            .param("lat", l.latitude?.toDoubleOrNull()).param("long", l.longitude?.toDoubleOrNull())
            .update()
    }
}
