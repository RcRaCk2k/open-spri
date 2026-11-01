package de.openspri.rest.partner

import de.openspri.auth.AuditLog
import de.openspri.domain.partner.AgreementDocument
import de.openspri.domain.partner.FrameworkContract
import de.openspri.domain.partner.PartnerAdministration
import de.openspri.domain.partner.PartnerAgreements
import de.openspri.domain.partner.PartnerId
import de.openspri.domain.partner.PowerOfAttorney
import de.openspri.domain.partner.ProjectAgreement
import de.openspri.domain.partner.Validity
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import de.openspri.rest.order.enumValue
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import io.swagger.v3.oas.annotations.tags.Tag
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
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** Rahmenverträge, Vollmachten and Projektvereinbarungen per partner (AUFGABE 5.7, 5.9, R-11, R-12). */
@RestController
@RequestMapping("/api/v1/partners/{partnerId}", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Partner", description = "Gegenstellen: Endpunkt, Signaturzertifikate, freigeschaltete IPs (AUFGABE 5.9)")
class AgreementController(
    private val partners: PartnerAdministration,
    private val agreements: PartnerAgreements,
    private val audit: AuditLog,
) {
    @Schema(description = "Rahmenvertrag: Auftraggeber- und Leistungsnummer, unter denen Aufträge laufen")
    data class ContractDto(
        @field:Schema(accessMode = Schema.AccessMode.READ_ONLY) val id: Long? = null,
        @field:Schema(description = "unsere Rolle: AUFTRAGGEBER (wir bestellen beim Partner) oder LEISTUNGSERBRINGER (der Partner bestellt bei uns)", requiredMode = REQUIRED)
        val role: String,
        @field:Schema(description = "Auftraggebernummer", example = "AG4711", requiredMode = REQUIRED) val clientNumber: String,
        @field:Schema(description = "Leistungsnummer; leer = alle", example = "LN0815") val serviceNumber: String? = null,
        val description: String? = null,
        val validFrom: LocalDate? = null,
        val validTo: LocalDate? = null,
    )

    @Schema(description = "Vollmacht: der Auftraggeber erlaubt dem Besteller, in seinem Namen zu bestellen (R-11)")
    data class PowerOfAttorneyDto(
        @field:Schema(accessMode = Schema.AccessMode.READ_ONLY) val id: Long? = null,
        @field:Schema(description = "Auftraggebernummer des Bestellers (Block besteller)", requiredMode = REQUIRED) val ordererClientNumber: String,
        @field:Schema(description = "Leistungsnummer des Bestellers", requiredMode = REQUIRED) val ordererServiceNumber: String,
        @field:Schema(description = "Auftraggebernummer des Vollmachtgebers", requiredMode = REQUIRED) val clientNumber: String,
        @field:Schema(description = "Leistungsnummer des Vollmachtgebers", requiredMode = REQUIRED) val serviceNumber: String,
        val description: String? = null,
        val validFrom: LocalDate? = null,
        val validTo: LocalDate? = null,
        @field:Schema(description = "Dateiname der hinterlegten Vollmacht (GET …/document)", accessMode = Schema.AccessMode.READ_ONLY) val documentName: String? = null,
    )

    @Schema(description = "Projektvereinbarung: Projektkenner, die mit dem Partner vereinbart sind (R-12)")
    data class ProjectDto(
        @field:Schema(accessMode = Schema.AccessMode.READ_ONLY) val id: Long? = null,
        @field:Schema(description = "Projektkenner", requiredMode = REQUIRED) val projectId: String,
        val description: String? = null,
        val validFrom: LocalDate? = null,
        val validTo: LocalDate? = null,
    )

    // --- Rahmenverträge ---------------------------------------------------------------------------

    @GetMapping("/contracts")
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Rahmenverträge des Partners")
    fun contracts(@PathVariable partnerId: Long): List<ContractDto> = agreements.contracts(partner(partnerId)).map(::toDto)

    @PostMapping("/contracts", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Rahmenvertrag anlegen", description = "Sobald ein Vertrag (Rolle LEISTUNGSERBRINGER) hinterlegt ist, prüft open-spri eingehende Aufträge dagegen.")
    fun createContract(@PathVariable partnerId: Long, @RequestBody body: ContractDto) = saveContract(partnerId, null, body)

    @PutMapping("/contracts/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Rahmenvertrag ändern")
    fun updateContract(@PathVariable partnerId: Long, @PathVariable id: Long, @RequestBody body: ContractDto) = saveContract(partnerId, id, body)

    @DeleteMapping("/contracts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Rahmenvertrag löschen")
    fun deleteContract(@PathVariable partnerId: Long, @PathVariable id: Long) {
        if (!agreements.deleteContract(partner(partnerId), id)) throw NoSuchElementException("Rahmenvertrag $id existiert nicht")
        audit.record(currentActor(), "partner.contract.delete", "partner:$partnerId", "id=$id")
    }

    private fun saveContract(partnerId: Long, id: Long?, body: ContractDto): ContractDto {
        val saved = agreements.saveContract(
            FrameworkContract(
                id, partner(partnerId), enumValue(body.role, "role"), field(body.clientNumber, "clientNumber", 10),
                body.serviceNumber?.let { field(it, "serviceNumber", 10) }, body.description, Validity(body.validFrom, body.validTo),
            ),
        )
        audit.record(currentActor(), "partner.contract.save", "partner:$partnerId", "id=${saved.id} client=${saved.clientNumber}")
        return toDto(saved)
    }

    // --- Vollmachten ------------------------------------------------------------------------------

    @GetMapping("/powers-of-attorney")
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Vollmachten für Bestellungen im Namen Dritter", description = "Der Partner ist der Besteller, der uns die Aufträge schickt.")
    fun powers(@PathVariable partnerId: Long): List<PowerOfAttorneyDto> = agreements.powersOfAttorney(partner(partnerId)).map(::toDto)

    @PostMapping("/powers-of-attorney", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Vollmacht anlegen", description = "Ein eingehender Auftrag mit Block besteller ohne gültige Vollmacht erhält den Befund R-11 (Grundlage für die ABBM).")
    fun createPower(@PathVariable partnerId: Long, @RequestBody body: PowerOfAttorneyDto) = savePower(partnerId, null, body)

    @PutMapping("/powers-of-attorney/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Vollmacht ändern")
    fun updatePower(@PathVariable partnerId: Long, @PathVariable id: Long, @RequestBody body: PowerOfAttorneyDto) = savePower(partnerId, id, body)

    @DeleteMapping("/powers-of-attorney/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Vollmacht löschen (samt Dokument)")
    fun deletePower(@PathVariable partnerId: Long, @PathVariable id: Long) {
        if (!agreements.deletePowerOfAttorney(partner(partnerId), id)) throw NoSuchElementException("Vollmacht $id existiert nicht")
        audit.record(currentActor(), "partner.power-of-attorney.delete", "partner:$partnerId", "id=$id")
    }

    @PutMapping("/powers-of-attorney/{id}/document", consumes = [MediaType.APPLICATION_PDF_VALUE, MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Vollmacht als Dokument hinterlegen", description = "PDF, PNG oder JPEG, höchstens 10 MB. Ersetzt ein vorhandenes Dokument.")
    fun uploadDocument(
        @PathVariable partnerId: Long,
        @PathVariable id: Long,
        @Parameter(description = "Dateiname") @RequestParam(defaultValue = "vollmacht") name: String,
        @RequestHeader(HttpHeaders.CONTENT_TYPE) contentType: String,
        @RequestBody content: ByteArray,
    ) {
        require(content.isNotEmpty()) { "Das Dokument ist leer" }
        require(content.size <= MAX_DOCUMENT_BYTES) { "Das Dokument ist größer als 10 MB" }
        val fileName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(200)
        if (!agreements.saveDocument(partner(partnerId), id, AgreementDocument(fileName, MediaType.parseMediaType(contentType).toString(), content))) {
            throw NoSuchElementException("Vollmacht $id existiert nicht")
        }
        audit.record(currentActor(), "partner.power-of-attorney.document", "partner:$partnerId", "id=$id bytes=${content.size}")
    }

    @GetMapping("/powers-of-attorney/{id}/document", produces = [MediaType.ALL_VALUE])
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Hinterlegte Vollmacht herunterladen", description = "Das Dokument enthält personenbezogene Daten; jeder Abruf wird im Audit-Log festgehalten.")
    fun document(@PathVariable partnerId: Long, @PathVariable id: Long): ResponseEntity<ByteArray> {
        val document = agreements.document(partner(partnerId), id) ?: throw NoSuchElementException("Zur Vollmacht $id ist kein Dokument hinterlegt")
        audit.record(currentActor(), "partner.power-of-attorney.document.read", "partner:$partnerId", "id=$id")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(document.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(document.name).build().toString())
            .body(document.content)
    }

    private fun savePower(partnerId: Long, id: Long?, body: PowerOfAttorneyDto): PowerOfAttorneyDto {
        val saved = agreements.savePowerOfAttorney(
            PowerOfAttorney(
                id, partner(partnerId), field(body.ordererClientNumber, "ordererClientNumber", 10), field(body.ordererServiceNumber, "ordererServiceNumber", 10),
                field(body.clientNumber, "clientNumber", 10), field(body.serviceNumber, "serviceNumber", 10), body.description, Validity(body.validFrom, body.validTo),
            ),
        )
        audit.record(currentActor(), "partner.power-of-attorney.save", "partner:$partnerId", "id=${saved.id}")
        return toDto(saved)
    }

    // --- Projektvereinbarungen --------------------------------------------------------------------

    @GetMapping("/projects")
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Projektvereinbarungen des Partners")
    fun projects(@PathVariable partnerId: Long): List<ProjectDto> = agreements.projects(partner(partnerId)).map(::toDto)

    @PostMapping("/projects", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Projektvereinbarung anlegen", description = "Ohne gültige Vereinbarung wird ein Auftrag mit diesem Projektkenner abgewiesen (eigener: 409, eingehender: Befund R-12).")
    fun createProject(@PathVariable partnerId: Long, @RequestBody body: ProjectDto) = saveProject(partnerId, null, body)

    @PutMapping("/projects/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Projektvereinbarung ändern")
    fun updateProject(@PathVariable partnerId: Long, @PathVariable id: Long, @RequestBody body: ProjectDto) = saveProject(partnerId, id, body)

    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Projektvereinbarung löschen")
    fun deleteProject(@PathVariable partnerId: Long, @PathVariable id: Long) {
        if (!agreements.deleteProject(partner(partnerId), id)) throw NoSuchElementException("Projektvereinbarung $id existiert nicht")
        audit.record(currentActor(), "partner.project.delete", "partner:$partnerId", "id=$id")
    }

    private fun saveProject(partnerId: Long, id: Long?, body: ProjectDto): ProjectDto {
        val partner = partner(partnerId)
        val projectId = field(body.projectId, "projectId", 50)
        if (agreements.projects(partner).any { it.projectId == projectId && it.id != id }) {
            throw IllegalStateException("Projektkenner $projectId ist für diesen Partner schon hinterlegt")
        }
        val saved = agreements.saveProject(ProjectAgreement(id, partner, projectId, body.description, Validity(body.validFrom, body.validTo)))
        audit.record(currentActor(), "partner.project.save", "partner:$partnerId", "id=${saved.id} project=$projectId")
        return toDto(saved)
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun partner(id: Long): PartnerId = partners.get(PartnerId(id))?.id ?: throw NoSuchElementException("Partner $id existiert nicht")

    private fun field(value: String, name: String, max: Int): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= max) { "$name: 1 bis $max Zeichen" }
        return trimmed
    }

    private fun toDto(c: FrameworkContract) = ContractDto(c.id, c.role.name, c.clientNumber, c.serviceNumber, c.description, c.validity.from, c.validity.to)
    private fun toDto(p: PowerOfAttorney) = PowerOfAttorneyDto(
        p.id, p.ordererClientNumber, p.ordererServiceNumber, p.clientNumber, p.serviceNumber, p.description, p.validity.from, p.validity.to, p.documentName,
    )
    private fun toDto(p: ProjectAgreement) = ProjectDto(p.id, p.projectId, p.description, p.validity.from, p.validity.to)

    private companion object {
        const val MAX_DOCUMENT_BYTES = 10 * 1024 * 1024
    }
}
