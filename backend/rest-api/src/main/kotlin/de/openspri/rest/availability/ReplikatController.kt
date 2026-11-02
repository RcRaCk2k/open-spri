package de.openspri.rest.availability

import de.openspri.auth.AuditLog
import de.openspri.domain.availability.ReplikatRelease
import de.openspri.domain.availability.ReplikatReleases
import de.openspri.domain.availability.ReplikatSnapshot
import de.openspri.domain.availability.ReplikatSnapshots
import de.openspri.domain.partner.PartnerAdministration
import de.openspri.domain.partner.PartnerId
import de.openspri.replikat.ReplikatExporter
import de.openspri.replikat.ReplikatJson
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate

@Schema(description = "Ein bereitgestelltes Replikat (ZIP) mit Metadatei")
data class ReplikatFileDto(
    @field:Schema(example = "GFNORD_20261102.zip", requiredMode = Schema.RequiredMode.REQUIRED) val fileName: String,
    @field:Schema(example = "GFNORD_20261102_meta.xml", requiredMode = Schema.RequiredMode.REQUIRED) val metaFileName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val date: LocalDate,
    @field:Schema(description = "FULL oder DELTA", requiredMode = Schema.RequiredMode.REQUIRED) val kind: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val md5: String,
    @field:Schema(description = "Anzahl Datensätze", requiredMode = Schema.RequiredMode.REQUIRED) val records: Int,
    @field:Schema(description = "Größe in Byte", requiredMode = Schema.RequiredMode.REQUIRED) val size: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
) {
    companion object {
        fun of(s: ReplikatSnapshot) = ReplikatFileDto(s.fileName, s.metaFileName, s.date, s.kind.name, s.md5, s.records, s.size, s.createdAt)
    }
}

@Schema(description = "Freigabe unseres Replikats für einen Partner (bilaterale Vereinbarung)")
data class ReleaseDto(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val partnerId: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val enabled: Boolean = true,
    @field:Schema(description = "zusätzlich ein Deltareplikat bereitstellen", requiredMode = Schema.RequiredMode.REQUIRED) val delta: Boolean = true,
    @field:Schema(description = "optionale Replikat-Felder, die dieser Partner nicht erhält, z. B. geo, kont, ane") val omittedFields: List<String> = emptyList(),
    @field:Schema(description = "nur Standorte mit diesen Kontingenten (`kont`); leer = alle") val quotas: List<String> = emptyList(),
) {
    companion object {
        fun of(r: ReplikatRelease) = ReleaseDto(r.partnerId.value, r.enabled, r.delta, r.omittedFields.sorted(), r.quotas.sorted())
    }
}

/**
 * Our Replikat (Leistungserbringer, AUFGABE 5.4): releases per partner and the download for partners under
 * `/replikat/` (outside `/api/v1`, as for a download server, Replikat 3.1).
 */
@RestController
@Tag(name = "Verfügbarkeit", description = "Eigene Standorte, Replikat, Online-Abfrage und Suche bei Partnern (AUFGABE 5.4)")
class ReplikatController(
    private val releases: ReplikatReleases,
    private val snapshots: ReplikatSnapshots,
    private val exporter: ReplikatExporter,
    private val partners: PartnerAdministration,
    private val access: PartnerAccess,
    private val audit: AuditLog,
) {
    // --- releases (administration) -------------------------------------------------------------------

    @GetMapping("/api/v1/replikat/releases", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.AVAILABILITY_READ)
    @Operation(summary = "Freigaben unseres Replikats")
    fun releases(): List<ReleaseDto> = releases.list().map(ReleaseDto::of)

    @PutMapping("/api/v1/replikat/releases/{partnerId}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.AVAILABILITY_ADMIN)
    @Operation(
        summary = "Replikat für einen Partner freigeben oder ändern",
        description = "Ab dem nächsten Lauf (täglich, `OPENSPRI_REPLIKAT_EXPORT_CRON`) erhält der Partner ein Voll- und ggf. Deltareplikat.",
    )
    fun release(@PathVariable partnerId: Long, @RequestBody body: ReleaseDto): ReleaseDto {
        partners.get(PartnerId(partnerId)) ?: throw NoSuchElementException("Partner $partnerId existiert nicht")
        val unknown = body.omittedFields.toSet() - ReplikatJson.OPTIONAL_FIELDS
        require(unknown.isEmpty()) { "omittedFields: nicht optional bzw. unbekannt: $unknown; zulässig: ${ReplikatJson.OPTIONAL_FIELDS.sorted().joinToString()}" }
        val release = ReplikatRelease(PartnerId(partnerId), body.enabled, body.delta, body.omittedFields.toSet(), body.quotas.toSet())
        releases.save(release)
        audit.record(currentActor(), "replikat.release", "partner:$partnerId", "enabled=${body.enabled} delta=${body.delta}")
        return ReleaseDto.of(release)
    }

    @DeleteMapping("/api/v1/replikat/releases/{partnerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Requires.AVAILABILITY_ADMIN)
    @Operation(summary = "Freigabe entfernen")
    fun deleteRelease(@PathVariable partnerId: Long) {
        if (!releases.delete(PartnerId(partnerId))) throw NoSuchElementException("Keine Freigabe für Partner $partnerId")
        audit.record(currentActor(), "replikat.release.delete", "partner:$partnerId", null)
    }

    @PostMapping("/api/v1/replikat/releases/{partnerId}/run", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.AVAILABILITY_ADMIN)
    @Operation(summary = "Replikat für einen Partner jetzt erzeugen", description = "Ersetzt die Dateien des heutigen Tages.")
    fun run(@PathVariable partnerId: Long): List<ReplikatFileDto> {
        val release = releases.get(PartnerId(partnerId)) ?: throw NoSuchElementException("Keine Freigabe für Partner $partnerId")
        val result = exporter.export(release)
        audit.record(currentActor(), "replikat.export", "partner:$partnerId", "records=${result.full?.records}")
        return listOfNotNull(result.full, result.delta).map(ReplikatFileDto::of)
    }

    @GetMapping("/api/v1/replikat/releases/{partnerId}/files", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.AVAILABILITY_READ)
    @Operation(summary = "Bereitgestellte Replikate eines Partners", description = "Die letzten 31 Tage (Replikat 3.1).")
    fun files(@PathVariable partnerId: Long): List<ReplikatFileDto> = snapshots.list(PartnerId(partnerId)).map(ReplikatFileDto::of)

    // --- download for partners ----------------------------------------------------------------------

    @GetMapping("/replikat/", "/replikat", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Replikate für den Partner (Download-Verzeichnis)",
        description = "Nur mit Partner-Token (Bearer oder Basic-Auth mit dem Token als Passwort) aus einer freigeschalteten IP. Neueste zuerst.",
    )
    fun listForPartner(request: HttpServletRequest): List<ReplikatFileDto> = snapshots.list(access.require(request).id).map(ReplikatFileDto::of)

    @GetMapping("/replikat/{file}", produces = [MediaType.ALL_VALUE])
    @Operation(summary = "Replikat oder Metadatei herunterladen", description = "`<CarrierCode>_<JJJJMMTT>.zip`, `…_delta.zip`, `…_meta.xml`")
    fun download(request: HttpServletRequest, @PathVariable file: String): ResponseEntity<ByteArray> {
        val partner = access.require(request)
        val content = snapshots.file(partner.id, file) ?: throw NoSuchElementException("Datei $file existiert nicht")
        val type = if (file.endsWith(".xml")) MediaType.APPLICATION_XML else MediaType.parseMediaType("application/zip")
        return ResponseEntity.ok().contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
            .body(content)
    }
}
