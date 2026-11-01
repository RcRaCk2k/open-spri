package de.openspri.rest.setup

import de.openspri.auth.AuditLog
import de.openspri.domain.settings.SettingsRepository
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import de.openspri.soap.SigningKeyHolder
import de.openspri.soap.SigningKeyService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.Base64

@RestController
@RequestMapping("/api/v1/settings", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Einstellungen", description = "Firmendaten, Optionen und Signaturschlüssel")
class SettingsController(
    private val settings: SettingsRepository,
    private val keys: SigningKeyService,
    private val holder: SigningKeyHolder,
    private val audit: AuditLog,
) {
    @Schema(description = "Unser Signaturzertifikat (WS-Security). Das PEM geht an jeden Partner.")
    data class SigningKeyInfo(
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subject: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val issuer: String,
        @field:Schema(description = "dezimal, wie im Feld signaturId/serial", requiredMode = Schema.RequiredMode.REQUIRED) val serialNumber: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val notAfter: Instant,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val keyBits: Int,
        @field:Schema(description = "KEYSTORE_FILE, DATABASE, TEMPORARY", requiredMode = Schema.RequiredMode.REQUIRED) val source: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val certificatePem: String,
    )

    @Schema(description = "Neuen Signaturschlüssel erzeugen oder übernehmen")
    data class NewSigningKey(
        val commonName: String? = null,
        val pkcs12Base64: String? = null,
        val pkcs12Password: String? = null,
    )

    @GetMapping
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Firmendaten und Optionen")
    fun get(): CompanySettingsDto = CompanySettingsDto.of(settings.load() ?: throw NoSuchElementException("Ersteinrichtung nicht abgeschlossen"))

    @PutMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.SETTINGS_ADMIN)
    @Operation(summary = "Firmendaten und Optionen ändern")
    fun put(@RequestBody body: CompanySettingsDto): CompanySettingsDto {
        val updated = body.toDomain()
        settings.save(updated)
        audit.record(currentActor(), "settings.update", "company:${updated.carrierCode}")
        return CompanySettingsDto.of(updated)
    }

    @GetMapping("/signing-key")
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Unser Signaturzertifikat", description = "Inklusive PEM zur Weitergabe an Partner.")
    fun signingKey(): SigningKeyInfo {
        val certificate = holder.current().certificate
        return SigningKeyInfo(
            certificate.subjectX500Principal.name, certificate.issuerX500Principal.name, certificate.serialNumber.toString(),
            certificate.notAfter.toInstant(), de.openspri.soap.SigningCredentials.rsaKeyBits(certificate), holder.source().name, keys.certificatePem(),
        )
    }

    @PostMapping("/signing-key", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.SETTINGS_ADMIN)
    @Operation(
        summary = "Signaturschlüssel wechseln",
        description = "Erzeugt einen neuen Schlüssel oder übernimmt einen PKCS#12-Keystore. Wirkt sofort für alle neuen Nachrichten. " +
            "Das neue Zertifikat muss allen Partnern vorab mitgeteilt werden (Partner hinterlegen mehrere Zertifikate während des Wechsels).",
    )
    fun newSigningKey(@RequestBody body: NewSigningKey): SigningKeyInfo {
        val actor = currentActor()
        if (body.pkcs12Base64 != null) keys.import(Base64.getDecoder().decode(body.pkcs12Base64), body.pkcs12Password.orEmpty().toCharArray(), actor.label)
        else keys.generate(body.commonName ?: "S/PRI ${settings.load()?.name ?: "open-spri"}", actor.label)
        audit.record(actor, "signing-key.change", "signing-key")
        return signingKey()
    }
}
