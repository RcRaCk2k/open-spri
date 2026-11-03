package de.openspri.rest.partner

import de.openspri.auth.AuditLog
import de.openspri.domain.partner.IpNetwork
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerAdministration
import de.openspri.domain.partner.PartnerId
import de.openspri.domain.settings.SettingsRepository
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import de.openspri.soap.SigningKeyService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant

@RestController
@RequestMapping("/api/v1/partners", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Partner", description = "Gegenstellen: Endpunkt, Signaturzertifikate, freigeschaltete IPs (AUFGABE 5.9)")
class PartnerController(
    private val partners: PartnerAdministration,
    private val settings: SettingsRepository,
    private val keys: SigningKeyService,
    private val audit: AuditLog,
) {
    @Schema(description = "Ein Signaturzertifikat des Partners")
    data class CertificateDto(
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: Long,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subject: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val issuer: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val serialNumber: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val notAfter: Instant,
        @field:Schema(description = "RSA-Schlüssellänge; das AK verlangt ≥ 3072 (bestehende 2048 bis Laufzeitende)", requiredMode = Schema.RequiredMode.REQUIRED)
        val keyBits: Int,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val expired: Boolean,
    )

    @Schema(description = "Ein Partner")
    data class PartnerDto(
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: Long,
        @field:Schema(example = "Glasfaser Nord GmbH", requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
        @field:Schema(description = "ITU Carrier Code des Partners", example = "GFNORD", requiredMode = Schema.RequiredMode.REQUIRED) val carrierCode: String,
        @field:Schema(description = "S/PRI-Endpunkt des Partners (aus seiner WSDL)", example = "https://spri.partner.example/spri/v4") val endpointUrl: String?,
        @field:Schema(description = "technischer Kontakt; erhält die Ausfall-Mail (11.2.3.2)") val technicalContactEmail: String?,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val enabled: Boolean,
        @field:Schema(description = "über den Egress-Proxy senden (SPRI_OUTBOUND_PROXY), falls einer konfiguriert ist", requiredMode = Schema.RequiredMode.REQUIRED)
        val useOutboundProxy: Boolean,
        @field:Schema(description = "Outbound-IPs des Partners, die unseren Endpunkt aufrufen dürfen (CIDR)", requiredMode = Schema.RequiredMode.REQUIRED)
        val allowedSourceNetworks: List<String>,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val certificates: List<CertificateDto>,
    )

    @Schema(description = "Neuer Partner")
    data class CreatePartnerRequest(
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val carrierCode: String,
        val endpointUrl: String? = null,
        val technicalContactEmail: String? = null,
        val enabled: Boolean = true,
        @field:Schema(description = "über den Egress-Proxy senden, falls einer konfiguriert ist") val useOutboundProxy: Boolean = true,
        @field:Schema(example = "[\"203.0.113.10/32\"]") val allowedSourceNetworks: List<String> = emptyList(),
        @field:Schema(description = "Signaturzertifikate als PEM") val certificatesPem: List<String> = emptyList(),
    )

    @Schema(description = "Änderungen; nicht angegebene Felder bleiben unverändert")
    data class UpdatePartnerRequest(
        val name: String? = null,
        val carrierCode: String? = null,
        val endpointUrl: String? = null,
        val technicalContactEmail: String? = null,
        val enabled: Boolean? = null,
        val useOutboundProxy: Boolean? = null,
    )

    @Schema(description = "Ein Zertifikat als PEM")
    data class CertificateRequest(@field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val pem: String)

    @Schema(description = "Alles, was ein neuer Partner von uns braucht")
    data class OnboardingSheet(
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val company: String,
        @field:Schema(description = "unser ITU Carrier Code", requiredMode = Schema.RequiredMode.REQUIRED) val carrierCode: String,
        @field:Schema(description = "unser S/PRI-Endpunkt") val spriEndpoint: String?,
        @field:Schema(description = "WSDL mit unserem Endpunkt: GET /api/v1/partners/wsdl", requiredMode = Schema.RequiredMode.REQUIRED) val wsdlPath: String,
        @field:Schema(description = "unsere Outbound-IPs zum Freischalten", requiredMode = Schema.RequiredMode.REQUIRED) val outboundAddresses: List<String>,
        @field:Schema(description = "unser Signaturzertifikat (PEM)", requiredMode = Schema.RequiredMode.REQUIRED) val signingCertificatePem: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val technicalContact: String,
        @field:Schema(description = "unterstützte S/PRI-Version", requiredMode = Schema.RequiredMode.REQUIRED) val spriVersion: String,
    )

    @GetMapping
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Partner auflisten")
    fun list(): List<PartnerDto> = partners.list().map(::toDto)

    @GetMapping("/{id}")
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Partner lesen")
    fun get(@PathVariable id: Long): PartnerDto = toDto(find(id))

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Partner anlegen")
    fun create(@RequestBody body: CreatePartnerRequest): PartnerDto {
        val partner = Partner(
            PartnerId(0), body.name.trim(), body.carrierCode.trim().uppercase(), body.certificatesPem.map(::parsePem),
            body.allowedSourceNetworks.map(IpNetwork::parse), body.enabled, body.endpointUrl?.let(::validateUrl), body.technicalContactEmail,
            body.useOutboundProxy,
        )
        val id = partners.create(partner)
        audit.record(currentActor(), "partner.create", "partner:${id.value}", "carrierCode=${partner.carrierCode}")
        return get(id.value)
    }

    @PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Partner ändern")
    fun update(@PathVariable id: Long, @RequestBody body: UpdatePartnerRequest): PartnerDto {
        val current = find(id)
        partners.update(
            current.copy(
                name = body.name?.trim() ?: current.name,
                carrierCode = body.carrierCode?.trim()?.uppercase() ?: current.carrierCode,
                endpointUrl = body.endpointUrl?.let(::validateUrl) ?: current.endpointUrl,
                technicalContactEmail = body.technicalContactEmail ?: current.technicalContactEmail,
                enabled = body.enabled ?: current.enabled,
                useOutboundProxy = body.useOutboundProxy ?: current.useOutboundProxy,
            ),
        )
        audit.record(currentActor(), "partner.update", "partner:$id")
        return get(id)
    }

    @PutMapping("/{id}/source-networks", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Freigeschaltete IPs ersetzen", description = "Liste in CIDR-Notation, z. B. `203.0.113.10/32`. Hostnamen sind nicht zulässig.")
    fun networks(@PathVariable id: Long, @RequestBody networks: List<String>): PartnerDto {
        find(id)
        partners.replaceNetworks(PartnerId(id), networks.map(IpNetwork::parse))
        audit.record(currentActor(), "partner.networks", "partner:$id", networks.joinToString())
        return get(id)
    }

    @PostMapping("/{id}/certificates", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Signaturzertifikat hinzufügen", description = "Während eines Zertifikatswechsels dürfen mehrere Zertifikate gültig sein.")
    fun addCertificate(@PathVariable id: Long, @RequestBody body: CertificateRequest): PartnerDto {
        find(id)
        val certificate = parsePem(body.pem)
        partners.addCertificate(PartnerId(id), certificate)
        audit.record(currentActor(), "partner.certificate.add", "partner:$id", certificate.subjectX500Principal.name)
        return get(id)
    }

    @DeleteMapping("/{id}/certificates/{certificateId}")
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(summary = "Signaturzertifikat entfernen")
    fun removeCertificate(@PathVariable id: Long, @PathVariable certificateId: Long): ResponseEntity<Unit> {
        if (!partners.removeCertificate(PartnerId(id), certificateId)) throw NoSuchElementException("Zertifikat $certificateId nicht gefunden")
        audit.record(currentActor(), "partner.certificate.remove", "partner:$id", "certificate=$certificateId")
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/onboarding-sheet")
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Onboarding-Blatt", description = "Unsere Angaben für einen neuen Partner: Endpunkt, WSDL, Outbound-IPs, Signaturzertifikat, Kontakt.")
    fun onboardingSheet(): OnboardingSheet {
        val company = settings.load() ?: throw NoSuchElementException("Ersteinrichtung nicht abgeschlossen")
        return OnboardingSheet(
            company.name, company.carrierCode, company.publicSpriEndpoint, "/api/v1/partners/wsdl", company.outboundAddresses,
            keys.certificatePem(), company.contactEmail, de.openspri.domain.InterfaceVersion.CURRENT.toString(),
        )
    }

    @GetMapping("/wsdl", produces = [MediaType.TEXT_XML_VALUE])
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(
        summary = "WSDL für Partner",
        description = "Die WSDL der AK (Anlage 3) mit unserem öffentlichen Endpunkt als Service-Adresse (Spezifikation 3.3: nur der Endpoint wird angepasst).",
    )
    fun wsdl(): ResponseEntity<String> {
        val endpoint = settings.load()?.publicSpriEndpoint ?: throw IllegalStateException("Öffentlicher S/PRI-Endpunkt ist nicht eingestellt (Einstellungen)")
        val wsdl = requireNotNull(javaClass.classLoader.getResourceAsStream("spri/v4/wsdl/spri.wsdl")).readBytes().toString(Charsets.UTF_8)
        val adapted = wsdl.replace(Regex("""<soap:address location="[^"]*"/>"""), """<soap:address location="${endpoint.replace("\"", "")}"/>""")
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"spri.wsdl\"").body(adapted)
    }

    private fun find(id: Long) = partners.get(PartnerId(id)) ?: throw NoSuchElementException("Partner $id nicht gefunden")

    private fun toDto(p: Partner) = PartnerDto(
        p.id.value, p.name, p.carrierCode, p.endpointUrl, p.technicalContactEmail, p.enabled, p.useOutboundProxy, p.allowedSourceNetworks.map { it.toString() },
        partners.certificates(p.id).map { (certId, c) ->
            CertificateDto(
                certId, c.subjectX500Principal.name, c.issuerX500Principal.name, c.serialNumber.toString(), c.notAfter.toInstant(),
                de.openspri.soap.SigningCredentials.rsaKeyBits(c), c.notAfter.toInstant().isBefore(Instant.now()),
            )
        },
    )

    private fun parsePem(pem: String): X509Certificate = try {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.trim().toByteArray())) as X509Certificate
    } catch (e: Exception) {
        throw IllegalArgumentException("Kein gültiges X.509-Zertifikat im PEM-Format: ${e.message}")
    }

    private fun validateUrl(url: String): String {
        val uri = java.net.URI(url.trim())
        require(uri.scheme == "https" || uri.scheme == "http") { "Endpunkt muss eine http(s)-URL sein" }
        require(!uri.host.isNullOrBlank()) { "Endpunkt ohne Host" }
        return uri.toString()
    }
}
