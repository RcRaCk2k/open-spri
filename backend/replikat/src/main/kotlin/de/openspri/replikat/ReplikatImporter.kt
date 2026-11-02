package de.openspri.replikat

import de.openspri.domain.availability.AvailabilityProvider
import de.openspri.domain.availability.AvailabilityQuery
import de.openspri.domain.availability.AvailabilitySource
import de.openspri.domain.availability.ImportStatus
import de.openspri.domain.availability.Location
import de.openspri.domain.availability.PartnerLocations
import de.openspri.domain.availability.ReplikatImport
import de.openspri.domain.availability.ReplikatImports
import de.openspri.domain.availability.SourceKind
import de.openspri.domain.partner.Partner
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.Base64

/** HTTP access to a partner's download server or instance, with the credentials of the source. */
open class SourceClient(private val timeout: Duration = Duration.ofMinutes(60)) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build()

    /** The content at [url], null on 404. Other errors are exceptions. */
    open fun get(source: AvailabilitySource, url: URI): ByteArray? {
        val request = HttpRequest.newBuilder(url).timeout(timeout).GET().apply {
            source.secret?.let { secret ->
                header("Authorization", source.username?.let { "Basic " + Base64.getEncoder().encodeToString("$it:$secret".toByteArray()) } ?: "Bearer $secret")
            }
        }.build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return when (response.statusCode()) {
            200 -> response.body()
            404 -> null
            else -> throw IllegalStateException("HTTP ${response.statusCode()} von $url")
        }
    }
}

/**
 * Daily import of a partner's Replikat (Auftraggeber, AUFGABE 5.4): meta file `<CC>_<YYYYMMDD>_meta.xml` of today,
 * otherwise of yesterday (the Replikat of the previous day is due at 06:00, Replikat 3.1); then the file it names,
 * MD5 check against the meta file, schema check (Replikat.xsd 1.7) and import. A delta is applied only on top of the
 * import of the previous day; otherwise the full Replikat is imported. Every run is recorded in [imports].
 */
class ReplikatImporter(
    private val locations: PartnerLocations,
    private val imports: ReplikatImports,
    private val client: SourceClient = SourceClient(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val maxBytes: Long = MAX_XML_BYTES,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(partner: Partner, source: AvailabilitySource): ReplikatImport {
        require(source.kind == SourceKind.REPLIKAT) { "Quelle ${source.id} ist keine Replikat-Quelle" }
        val started = clock.instant()
        return try {
            val result = import(partner, source) ?: throw IllegalStateException("Kein Replikat von ${partner.name} für ${dates().joinToString()} gefunden")
            imports.record(result.copy(startedAt = started, finishedAt = clock.instant()))
        } catch (e: Exception) {
            log.warn("Replikat import of {} failed: {}", partner.name, e.message)
            imports.record(ReplikatImport(null, source.id!!, partner.id, started, clock.instant(), ImportStatus.FAILED, error = e.message ?: e.javaClass.simpleName))
        }
    }

    private fun dates(): List<LocalDate> = LocalDate.now(clock).let { listOf(it, it.minusDays(1)) }

    private fun import(partner: Partner, source: AvailabilitySource): ReplikatImport? {
        val last = imports.last(source.id!!)?.takeIf { it.status == ImportStatus.OK }
        for (date in dates()) {
            val lastDate = last?.replikatDate
            if (last != null && lastDate != null && !lastDate.isBefore(date)) {
                // the Replikat of this day (or a newer one) is already in; never apply a delta twice
                val now = clock.instant()
                return ReplikatImport(null, source.id!!, partner.id, now, now, ImportStatus.OK, last.fileName, lastDate, last.delta, warnings = listOf("bereits importiert"))
            }
            // a delta only continues the import of the day before it
            val deltaPossible = source.delta && last?.replikatDate == date.minusDays(1)
            val names = listOfNotNull(
                ReplikatFiles.baseName(partner.carrierCode, date, delta = true).takeIf { deltaPossible },
                ReplikatFiles.baseName(partner.carrierCode, date, delta = false),
            )
            for (base in names) {
                val metaBytes = client.get(source, resolve(source.url, "${base}_meta.xml")) ?: continue
                val meta = ReplikatMeta.parse(metaBytes)
                val content = client.get(source, resolve(source.url, meta.fileName)) ?: throw IllegalStateException("${meta.fileName} fehlt trotz Metadatei")
                val md5 = ReplikatFiles.md5(content)
                if (!md5.equals(meta.md5, ignoreCase = true)) throw IllegalStateException("MD5 von ${meta.fileName} ist $md5, laut Metadatei ${meta.md5}")
                return apply(partner, source, meta.fileName, content)
            }
        }
        return null
    }

    private fun apply(partner: Partner, source: AvailabilitySource, fileName: String, content: ByteArray): ReplikatImport {
        val xml = bounded(ReplikatFiles.xml(content))
        val errors = ReplikatXml.validate(ByteArrayInputStream(xml))
        if (errors.isNotEmpty()) throw ReplikatInvalidException(errors)
        val records = mutableListOf<Location>()
        val header = ReplikatXml.read(ByteArrayInputStream(xml)) { records += it }
        val warnings = mutableListOf<String>()
        if (!header.carrierCode.equals(partner.carrierCode, ignoreCase = true)) {
            warnings += "carrierCode ${header.carrierCode} im Replikat, beim Partner ${partner.carrierCode}"
        }
        GeoCheck.implausible(records).takeIf { it > 0 }?.let { warnings += "$it Datensätze mit Koordinaten außerhalb Deutschlands oder vertauscht (SA-017)" }
        val now = clock.instant()
        if (header.delta) {
            val (added, changed, deleted) = locations.applyDelta(partner.id, records)
            return ReplikatImport(null, source.id!!, partner.id, now, now, ImportStatus.OK, fileName, header.date, true, records.size, added, changed, deleted, warnings)
        }
        locations.replaceAll(partner.id, records.map { it.copy(change = null) })
        return ReplikatImport(null, source.id!!, partner.id, now, now, ImportStatus.OK, fileName, header.date, false, records.size, records.size, 0, 0, warnings)
    }

    /** Protection against oversized (or ZIP bomb) files. */
    private fun bounded(input: InputStream): ByteArray {
        val bytes = input.readNBytes((maxBytes + 1).toInt().coerceAtLeast(0))
        if (bytes.size > maxBytes) throw IllegalStateException("Replikat größer als $maxBytes Byte (entpackt)")
        return bytes
    }

    companion object {
        /** 1 GiB unpacked; a nationwide Replikat of a large carrier fits, a ZIP bomb does not. */
        const val MAX_XML_BYTES = 1L shl 30

        fun resolve(base: String, file: String): URI = URI(base.trimEnd('/') + "/" + URLEncoder.encode(file, Charsets.UTF_8).replace("+", "%20"))
    }
}

/** Coordinates in Germany (ETRS89): latitude about 47.2 to 55.1, longitude about 5.8 to 15.1 (SA-017). */
object GeoCheck {
    fun plausible(latitude: Double, longitude: Double) = latitude in 47.0..55.2 && longitude in 5.5..15.5

    fun implausible(records: List<Location>) = records.count { l ->
        val lat = l.latitude?.toDoubleOrNull()
        val long = l.longitude?.toDoubleOrNull()
        lat != null && long != null && !plausible(lat, long)
    }
}

/** "Replikat (lokal)": search in the imported Replikat of the partner. */
class ReplikatProvider(private val locations: PartnerLocations) : AvailabilityProvider {
    override val kind = SourceKind.REPLIKAT
    override fun search(partner: Partner, source: AvailabilitySource, query: AvailabilityQuery) = locations.search(partner.id, query)
}

/**
 * "Online-Abfrage gegen eine Instanz dieser Software" (AUFGABE 5.4): `GET <url>/api/v1/availability` with the partner
 * token we got from the partner. The answer has the format of a Replikat record ([ReplikatJson]). The online query
 * has no search by coordinates.
 */
class OnlineProvider(private val client: SourceClient = SourceClient(Duration.ofSeconds(30))) : AvailabilityProvider {
    private val json = JsonMapper.builder().build()
    override val kind = SourceKind.ONLINE

    override fun search(partner: Partner, source: AvailabilitySource, query: AvailabilityQuery): List<Location> {
        val a = query.address
        val params = listOf("plz" to a.postalCode, "o" to a.city, "s" to a.street, "h" to a.houseNumber, "hz" to a.houseNumberSuffix, "aid" to a.addressId, "hid" to a.homeId)
            .filter { !it.second.isNullOrBlank() }
        if (params.isEmpty()) return emptyList()
        val url = URI(source.url.trimEnd('/') + "/api/v1/availability?" + params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, Charsets.UTF_8) })
        val body = client.get(source, url) ?: return emptyList()
        val tree = json.readTree(body)
        require(tree.isArray) { "Antwort von ${partner.name} ist keine Liste" }
        return tree.toList().mapIndexed { i, node -> ReplikatJson.fromJson(node, "$[$i]") }
    }
}
