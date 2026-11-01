package de.openspri.rest.setup

import de.openspri.auth.Actor
import de.openspri.auth.AuditLog
import de.openspri.auth.LocalUsers
import de.openspri.auth.Role
import de.openspri.domain.order.Transactions
import de.openspri.domain.settings.CompanySettings
import de.openspri.domain.settings.SettingsRepository
import de.openspri.domain.settings.WbciMode
import de.openspri.soap.SigningKeyHolder
import de.openspri.soap.SigningKeyService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.Base64

@RestController
@RequestMapping("/api/v1/setup", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Setup", description = "Ersteinrichtung einer neuen Installation (geschützt durch das Setup-Token aus dem Log)")
class SetupController(
    private val guard: SetupGuard,
    private val settings: SettingsRepository,
    private val users: LocalUsers,
    private val keys: SigningKeyService,
    private val holder: SigningKeyHolder,
    private val transactions: Transactions,
    private val audit: AuditLog,
) {
    @Schema(description = "Stand der Ersteinrichtung")
    data class SetupStatus(
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val completed: Boolean,
        @field:Schema(description = "Herkunft des aktuellen Signaturschlüssels: KEYSTORE_FILE, DATABASE, TEMPORARY", requiredMode = Schema.RequiredMode.REQUIRED)
        val signingKeySource: String,
    )

    @Schema(description = "Erster Administrator")
    data class AdminAccount(
        @field:Schema(example = "admin", requiredMode = Schema.RequiredMode.REQUIRED) val username: String,
        @field:Schema(example = "Administration", requiredMode = Schema.RequiredMode.REQUIRED) val displayName: String,
        @field:Schema(description = "mindestens 12 Zeichen", requiredMode = Schema.RequiredMode.REQUIRED) val password: String,
    )

    @Schema(description = "Signaturschlüssel: neu erzeugen (Standard) oder einen vorhandenen PKCS#12-Keystore übernehmen")
    data class SigningKeyChoice(
        @field:Schema(description = "true: neuen Schlüssel erzeugen (RSA 3072, selbstsigniert)", example = "true") val generate: Boolean = true,
        @field:Schema(description = "Name im Zertifikat beim Erzeugen", example = "S/PRI Signatur Beispiel GmbH") val commonName: String? = null,
        @field:Schema(description = "PKCS#12-Keystore (Base64) zum Übernehmen") val pkcs12Base64: String? = null,
        @field:Schema(description = "Passwort des Keystores") val pkcs12Password: String? = null,
    )

    @Schema(description = "Alle Angaben der Ersteinrichtung; wird in einem Schritt übernommen")
    data class SetupRequest(
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val admin: AdminAccount,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val company: CompanySettingsDto,
        @field:Schema(description = "ohne Angabe wird ein neuer Schlüssel erzeugt") val signingKey: SigningKeyChoice = SigningKeyChoice(),
    )

    @GetMapping
    @Operation(summary = "Stand der Ersteinrichtung", description = "Ohne Anmeldung aufrufbar. Die Oberfläche zeigt bei `completed=false` den Setup-Assistenten.")
    fun status() = SetupStatus(guard.completed(), holder.source().name)

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Ersteinrichtung abschließen",
        description = "Legt den ersten Administrator, die Firmendaten (inkl. ITU Carrier Code) und den Signaturschlüssel an. " +
            "Nur einmal möglich und nur mit dem Setup-Token aus dem Container-Log (Header `X-Setup-Token`).",
    )
    fun complete(
        @Parameter(description = "einmaliges Setup-Token aus dem Container-Log") @RequestHeader("X-Setup-Token", required = false) token: String?,
        @RequestBody body: SetupRequest,
    ): ResponseEntity<SetupStatus> {
        if (guard.completed()) return ResponseEntity.status(HttpStatus.CONFLICT).build()
        if (!guard.accepts(token)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val company = body.company.toDomain()
        val actor = Actor(Actor.Kind.SETUP, null, body.admin.username, emptySet())
        transactions.run {
            val admin = users.create(body.admin.username, body.admin.displayName, Role.ADMIN, body.admin.password, Instant.now())
            settings.save(company)
            if (holder.source() != SigningKeyHolder.Source.KEYSTORE_FILE) {
                val choice = body.signingKey
                if (choice.pkcs12Base64 != null) keys.import(Base64.getDecoder().decode(choice.pkcs12Base64), choice.pkcs12Password.orEmpty().toCharArray(), actor.label)
                else keys.generate(choice.commonName ?: "S/PRI ${company.name}", actor.label)
            }
            settings.markSetupCompleted(Instant.now())
            audit.record(actor.copy(kind = Actor.Kind.USER, id = admin.id), "setup.complete", "company:${company.carrierCode}")
        }
        guard.invalidate()
        return ResponseEntity.ok(status())
    }
}

@Schema(description = "Firmendaten und Optionen")
data class CompanySettingsDto(
    @field:Schema(example = "Beispiel GmbH", requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    val street: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    @field:Schema(description = "ITU Carrier Code (ICC), 1–6 Großbuchstaben/Ziffern", example = "BSPGMB", requiredMode = Schema.RequiredMode.REQUIRED)
    val carrierCode: String,
    @field:Schema(description = "Empfänger für Ausfall- und Wartungsmeldungen", example = "betrieb@example.de", requiredMode = Schema.RequiredMode.REQUIRED)
    val contactEmail: String,
    @field:Schema(description = "Wir bestellen bei Partnern", requiredMode = Schema.RequiredMode.REQUIRED) val roleAuftraggeber: Boolean,
    @field:Schema(description = "Wir bieten eigene Leitungen an", requiredMode = Schema.RequiredMode.REQUIRED) val roleLeistungserbringer: Boolean,
    val estEnabled: Boolean = true,
    val diagEnabled: Boolean = true,
    val connectivityEnabled: Boolean = true,
    val thirdPartyOrdersEnabled: Boolean = true,
    val wbciMode: WbciMode = WbciMode.SERVICE_PROVIDER,
    @field:Schema(description = "Öffentliche Adresse unseres S/PRI-Endpunkts (für die WSDL)", example = "https://spri.example.de/spri/v4")
    val publicSpriEndpoint: String? = null,
) {
    fun toDomain() = CompanySettings(
        name, street, postalCode, city, carrierCode.trim().uppercase(), contactEmail, roleAuftraggeber, roleLeistungserbringer,
        estEnabled, diagEnabled, connectivityEnabled, thirdPartyOrdersEnabled, wbciMode, publicSpriEndpoint,
    )

    companion object {
        fun of(s: CompanySettings) = CompanySettingsDto(
            s.name, s.street, s.postalCode, s.city, s.carrierCode, s.contactEmail, s.roleAuftraggeber, s.roleLeistungserbringer,
            s.estEnabled, s.diagEnabled, s.connectivityEnabled, s.thirdPartyOrdersEnabled, s.wbciMode, s.publicSpriEndpoint,
        )
    }
}
