package de.openspri.rest.availability

import de.openspri.auth.AuditLog
import de.openspri.domain.availability.AddressFilter
import de.openspri.domain.availability.AvailabilitySite
import de.openspri.domain.availability.AvailabilitySites
import de.openspri.domain.availability.ReplikatReleases
import de.openspri.replikat.ReplikatInvalidException
import de.openspri.replikat.ReplikatJson
import de.openspri.replikat.SiteImport
import de.openspri.replikat.SiteImportResult
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant

@Schema(description = "Ein eigener Standort (Leistungserbringer) mit dem Datensatz in Replikat-Feldnamen")
data class SiteDto(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: Long,
    @field:Schema(
        description = "Datensatz `ds` der Replikat.xsd 1.7 als JSON: `plz`, `o`, `s`, `h`, `pl` (Produkte), `tl` (Technologien) …",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val ds: JsonNode,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val updatedAt: Instant,
) {
    companion object {
        fun of(site: AvailabilitySite) = SiteDto(site.id!!, ReplikatJson.toJson(site.location), site.updatedAt)
    }
}

@Schema(description = "Ergebnis eines Imports eigener Standorte")
data class ImportDto(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val added: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val changed: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val deleted: Int,
    @field:Schema(description = "Standorte danach", requiredMode = Schema.RequiredMode.REQUIRED) val total: Int,
) {
    companion object {
        fun of(r: SiteImportResult) = ImportDto(r.added, r.changed, r.deleted, r.total)
    }
}

/**
 * Our own availabilities as Leistungserbringer (D-13: own table, maintained via REST or import) and the online query
 * for partners (AUFGABE 5.4: answer 1:1 with the fields of a Replikat record, OP-05).
 */
@RestController
@RequestMapping("/api/v1/availability", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Verfügbarkeit", description = "Eigene Standorte, Replikat, Online-Abfrage und Suche bei Partnern (AUFGABE 5.4)")
class AvailabilityController(
    private val sites: AvailabilitySites,
    private val releases: ReplikatReleases,
    private val access: PartnerAccess,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private val import by lazy { SiteImport(sites, clock) }

    @GetMapping
    @Operation(
        summary = "Online-Abfrage für Partner (Leistungserbringer)",
        description = "Nur mit Partner-Token (Rolle PARTNER) aus einer freigeschalteten IP und mit Replikat-Freigabe. Antwort: Datensätze wie im Replikat " +
            "(Feldnamen der Replikat.xsd 1.7), mit den für den Partner vereinbarten Feldern und Kontingenten. Mindestens `plz` mit `s`, oder `aid`, oder `hid`.",
    )
    fun query(
        request: HttpServletRequest,
        @RequestParam(required = false) plz: String?,
        @RequestParam(required = false) o: String?,
        @RequestParam(required = false) s: String?,
        @RequestParam(required = false) h: String?,
        @RequestParam(required = false) hz: String?,
        @RequestParam(required = false) aid: String?,
        @RequestParam(required = false) hid: String?,
    ): List<JsonNode> {
        val partner = access.require(request)
        val release = releases.get(partner.id)?.takeIf { it.enabled } ?: throw AccessDeniedException("Für ${partner.name} ist keine Verfügbarkeit freigegeben")
        require(!plz.isNullOrBlank() && !s.isNullOrBlank() || !aid.isNullOrBlank() || !hid.isNullOrBlank()) { "Mindestens plz und s, oder aid, oder hid angeben" }
        return sites.search(AddressFilter(plz, o, s, h, hz, hid, aid, limit = 100)).asSequence()
            .map { it.location }
            .filter { location -> release.quotas.isEmpty() || location.technologies.any { it.quota in release.quotas } }
            .map { ReplikatJson.toJson(ReplikatJson.without(it, release.omittedFields)) }
            .toList()
    }

    // --- own sites --------------------------------------------------------------------------------

    @GetMapping("/sites")
    @PreAuthorize(Requires.AVAILABILITY_READ)
    @Operation(summary = "Eigene Standorte suchen", description = "Neueste zuerst; `s` und `o` als Präfix. Blättern mit `cursor`.")
    fun sites(
        @RequestParam(required = false) plz: String?,
        @RequestParam(required = false) o: String?,
        @RequestParam(required = false) s: String?,
        @RequestParam(required = false) h: String?,
        @RequestParam(required = false) hz: String?,
        @RequestParam(required = false) aid: String?,
        @RequestParam(required = false) hid: String?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<SiteDto> = sites.search(AddressFilter(plz, o, s, h, hz, hid, aid, cursor, limit)).map(SiteDto::of)

    @GetMapping("/sites/{id}")
    @PreAuthorize(Requires.AVAILABILITY_READ)
    @Operation(summary = "Eigenen Standort lesen")
    fun site(@PathVariable id: Long): SiteDto = SiteDto.of(sites.get(id) ?: throw NoSuchElementException("Standort $id existiert nicht"))

    @PostMapping("/sites", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Requires.AVAILABILITY_ADMIN)
    @Operation(summary = "Eigenen Standort anlegen", description = "Body: Datensatz in Replikat-Feldnamen; geprüft gegen Replikat.xsd 1.7. 409 bei gleichem Standort.")
    fun create(@RequestBody ds: JsonNode): SiteDto = save(null, ds)

    @PutMapping("/sites/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.AVAILABILITY_ADMIN)
    @Operation(summary = "Eigenen Standort ändern")
    fun update(@PathVariable id: Long, @RequestBody ds: JsonNode): SiteDto = save(id, ds)

    @DeleteMapping("/sites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Requires.AVAILABILITY_ADMIN)
    @Operation(summary = "Eigenen Standort löschen", description = "Im nächsten Deltareplikat erscheint er mit DEL.")
    fun delete(@PathVariable id: Long) {
        if (!sites.delete(id)) throw NoSuchElementException("Standort $id existiert nicht")
        audit.record(currentActor(), "availability.site.delete", "site:$id", null)
    }

    @PostMapping("/sites/import", consumes = [MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, "application/zip", "text/csv"])
    @PreAuthorize(Requires.AVAILABILITY_ADMIN)
    @Operation(
        summary = "Eigene Standorte importieren",
        description = "Replikat (XML oder ZIP, Replikat.xsd 1.7) oder CSV (Spalten siehe docs/api/verfuegbarkeit.md). `mode=replace` (Standard): die Datei ist der " +
            "vollständige Bestand; `merge`: hinzufügen bzw. ersetzen. Ein Deltareplikat wird nach seinen Änderungskennzeichen angewendet. 400 mit Zeilennummern.",
    )
    fun import(
        request: HttpServletRequest,
        @Parameter(description = "replace oder merge") @RequestParam(defaultValue = "replace") mode: String,
        @RequestBody content: ByteArray,
    ): ImportDto {
        val importMode = SiteImport.Mode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) } ?: throw IllegalArgumentException("mode: replace oder merge")
        val result = if (request.contentType.orEmpty().startsWith("text/csv")) import.importCsv(String(content, Charsets.UTF_8), importMode)
        else import.importXml(content, importMode)
        audit.record(currentActor(), "availability.site.import", "sites", "mode=$mode added=${result.added} changed=${result.changed} deleted=${result.deleted}")
        return ImportDto.of(result)
    }

    private fun save(id: Long?, ds: JsonNode): SiteDto {
        val location = ReplikatJson.fromJson(ds, "$").copy(change = null)
        ReplikatJson.validate(location).takeIf { it.isNotEmpty() }?.let { throw ReplikatInvalidException(it) }
        if (id != null) sites.get(id) ?: throw NoSuchElementException("Standort $id existiert nicht")
        val saved = sites.save(AvailabilitySite(id, location, clock.instant()))
        audit.record(currentActor(), "availability.site.save", "site:${saved.id}", "${location.postalCode} ${location.street} ${location.houseNumber}")
        return SiteDto.of(saved)
    }
}
