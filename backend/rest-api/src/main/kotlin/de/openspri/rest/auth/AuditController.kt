package de.openspri.rest.auth

import de.openspri.auth.AuditEntry
import de.openspri.auth.AuditFilter
import de.openspri.auth.AuditTrail
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@Schema(description = "Ein Eintrag im Audit-Log")
data class AuditEntryDto(
    @field:Schema(requiredMode = REQUIRED) val id: Long,
    @field:Schema(requiredMode = REQUIRED) val at: Instant,
    @field:Schema(description = "user:<name>, token:<prefix> oder setup", example = "user:admin", requiredMode = REQUIRED) val actor: String,
    @field:Schema(description = "z. B. order.send, partner.create, message.raw", example = "user.create", requiredMode = REQUIRED) val action: String,
    @field:Schema(example = "user:m.muster", requiredMode = REQUIRED) val target: String,
    val details: String?,
) {
    companion object {
        fun of(e: AuditEntry) = AuditEntryDto(e.id, e.at, e.actor, e.action, e.target, e.details)
    }
}

/** Who changed what (NF-10): every changing action and every access to raw messages. */
@RestController
@RequestMapping("/api/v1/audit", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Audit-Log", description = "Wer hat was geändert oder Rohnachrichten gelesen")
class AuditController(private val trail: AuditTrail) {

    @GetMapping
    @PreAuthorize(Requires.AUDIT_READ)
    @Operation(
        summary = "Audit-Log durchsuchen",
        description = "Neueste zuerst. `actor` und `target` als Teilzeichenfolge, `action` als Präfix (z. B. `order.`). Blättern mit `cursor` = `id` des letzten Eintrags.",
    )
    fun search(
        @RequestParam(required = false) actor: String?,
        @Parameter(description = "Präfix, z. B. `order.`") @RequestParam(required = false) action: String?,
        @RequestParam(required = false) target: String?,
        @Parameter(description = "ab (einschließlich)") @RequestParam(required = false) from: Instant?,
        @Parameter(description = "bis (ausschließlich)") @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) cursor: Long?,
        @Parameter(description = "1 bis 500") @RequestParam(defaultValue = "50") limit: Int,
    ): List<AuditEntryDto> {
        require(limit in 1..500) { "limit: 1 bis 500" }
        return trail.search(AuditFilter(actor, action, target, from, to, cursor, limit)).map(AuditEntryDto::of)
    }
}
