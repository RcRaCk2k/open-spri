package de.openspri.rest.codes

import de.openspri.auth.AuditLog
import de.openspri.domain.message.MessageCodeStore
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import de.openspri.soap.config.MessageCodeCatalogHolder
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@Schema(description = "Ein Meldungscode (Anlage 5)")
data class MessageCodeDto(
    @field:Schema(example = "0000", requiredMode = REQUIRED) val code: String,
    @field:Schema(requiredMode = REQUIRED) val text: String,
    @field:Schema(description = "Meldungstypen, für die der Code vorgesehen ist", requiredMode = REQUIRED) val messageTypes: List<String>,
    @field:Schema(description = "Geschäftsfälle laut Tabelle; leer = alle", requiredMode = REQUIRED) val businessCases: List<String>,
    @field:Schema(description = "von open-spri ergänzt (spec-abweichungen.md), nicht aus der AK-Tabelle", requiredMode = REQUIRED) val localAddition: Boolean,
)

@Schema(description = "Die aktive Meldungscodetabelle")
data class MessageCodeTableDto(
    @field:Schema(description = "Dateiname der importierten Tabelle", requiredMode = REQUIRED) val source: String,
    @field:Schema(description = "SHA-256 der Datei; kennzeichnet die Version", requiredMode = REQUIRED) val sha256: String,
    @field:Schema(requiredMode = REQUIRED) val codes: List<MessageCodeDto>,
)

@RestController
@RequestMapping("/api/v1/message-codes", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Meldungscodes", description = "Meldungscodetabelle des AK (Anlage 5): anzeigen und neue Version importieren")
class MessageCodeController(
    private val holder: MessageCodeCatalogHolder,
    private val store: MessageCodeStore,
    private val audit: AuditLog,
) {
    @GetMapping
    @PreAuthorize(Requires.ORDERS_READ)
    @Operation(summary = "Aktive Meldungscodetabelle", description = "Optional gefiltert nach Meldungstyp, z. B. `?messageType=ABM`.")
    fun get(@RequestParam(required = false) messageType: String?): MessageCodeTableDto {
        val catalog = holder.get()
        val codes = catalog.codes.values
            .filter { messageType == null || messageType in it.messageTypes }
            .sortedBy { it.code }
            .map { MessageCodeDto(it.code, it.text, it.messageTypes.sorted(), it.businessCases.sorted(), it.localAddition) }
        return MessageCodeTableDto(catalog.source, catalog.sha256, codes)
    }

    @PostMapping("/import", consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE, "text/csv", "text/tab-separated-values"])
    @PreAuthorize(Requires.MESSAGE_CODES_ADMIN)
    @Operation(
        summary = "Neue Version importieren",
        description = "Die Datei so, wie sie aus dem AK-Confluence exportiert wurde (UTF-16-TSV oder CSV). Sie wird versioniert gespeichert " +
            "und sofort aktiv. Bei mehreren Instanzen übernehmen die anderen die Version beim nächsten Neustart.",
    )
    fun import(
        @RequestBody content: ByteArray,
        @Parameter(description = "Dateiname zur Nachverfolgung", example = "SPRI_Meldungscodetabelle.csv") @RequestParam(defaultValue = "upload.csv") filename: String,
    ): MessageCodeTableDto {
        require(content.isNotEmpty()) { "Leere Datei" }
        val catalog = store.importAndActivate(content, filename.take(200))
        holder.set(catalog)
        audit.record(currentActor(), "message-codes.import", "message-codes:${catalog.sha256.take(12)}", "${catalog.codes.size} codes from $filename")
        return get(null)
    }
}
