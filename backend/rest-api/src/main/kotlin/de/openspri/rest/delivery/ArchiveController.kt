package de.openspri.rest.delivery

import de.openspri.auth.AuditLog
import de.openspri.domain.exchange.ArchivedExchange
import de.openspri.domain.query.ArchiveQueries
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@Schema(description = "Eine archivierte SOAP-Nachricht mit Antwort (NF-09)")
data class ArchiveEntryDto(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: Long,
    @field:Schema(description = "INBOUND oder OUTBOUND", requiredMode = Schema.RequiredMode.REQUIRED) val direction: String,
    val operation: String?,
    val partnerId: Long?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val remoteAddress: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val startedAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val durationMillis: Long,
    val teqCode: String?,
    @field:Schema(description = "interner Grund einer Ablehnung; geht nie an den Partner") val rejectionReason: String?,
    val findings: String?,
    val signerSubject: String?,
    val externalOrderNumber: String?,
)

@RestController
@RequestMapping("/api/v1/archive")
@Tag(name = "Archiv", description = "Nachrichtenarchiv: SOAP-Nachrichten byte-genau, wie sie über die Leitung gingen")
class ArchiveController(private val archive: ArchiveQueries, private val audit: AuditLog) {

    @GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.ORDERS_READ)
    @Operation(summary = "Metadaten einer archivierten Nachricht")
    fun get(@PathVariable id: Long): ArchiveEntryDto = find(id).let {
        ArchiveEntryDto(
            id, it.direction.name, it.operation?.wsdlName, it.partnerId?.value, it.remoteAddress, it.startedAt, it.durationMillis,
            it.teqCode?.code, it.rejectionReason, it.findings, it.signerSubject, it.externalOrderNumber,
        )
    }

    @GetMapping("/{id}/request", produces = [MediaType.APPLICATION_XML_VALUE])
    @PreAuthorize(Requires.ORDERS_READ)
    @Operation(summary = "Anfrage (SOAP-Envelope, byte-genau)", description = "Enthält Endkundendaten; der Zugriff wird protokolliert.")
    fun request(@PathVariable id: Long): ByteArray {
        val entry = find(id)
        audit.record(currentActor(), "archive.read", "archive:$id", "request")
        return entry.request
    }

    @GetMapping("/{id}/response", produces = [MediaType.APPLICATION_XML_VALUE])
    @PreAuthorize(Requires.ORDERS_READ)
    @Operation(summary = "Antwort (TEQ, byte-genau)")
    fun response(@PathVariable id: Long): ByteArray =
        find(id).response ?: throw NoSuchElementException("Zu Nachricht $id gibt es keine Antwort")

    private fun find(id: Long): ArchivedExchange = archive.get(id) ?: throw NoSuchElementException("Archiveintrag $id existiert nicht")
}
