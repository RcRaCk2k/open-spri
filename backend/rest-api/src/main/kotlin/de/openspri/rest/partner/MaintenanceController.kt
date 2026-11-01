package de.openspri.rest.partner

import de.openspri.auth.AuditLog
import de.openspri.domain.partner.MaintenanceAnnouncement
import de.openspri.domain.partner.MaintenanceAnnouncements
import de.openspri.domain.partner.MaintenanceService
import de.openspri.domain.partner.PartnerId
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** "Wartung ankündigen" (NF-08, specification 11.5). */
@RestController
@RequestMapping("/api/v1/maintenance-announcements", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Betrieb", description = "Zustellung, Eingang, Archiv und Wartung")
class MaintenanceController(
    private val service: MaintenanceService,
    private val store: MaintenanceAnnouncements,
    private val audit: AuditLog,
) {
    @Schema(description = "Ankündigung eines Wartungsfensters")
    data class AnnouncementRequest(
        @field:Schema(example = "2026-11-20T20:00:00Z", requiredMode = REQUIRED) val start: Instant,
        @field:Schema(example = "2026-11-20T22:00:00Z", requiredMode = REQUIRED) val end: Instant,
        @field:Schema(example = "Wartung der S/PRI-Schnittstelle", requiredMode = REQUIRED) val subject: String,
        @field:Schema(description = "Text der Mail, z. B. Grund und Auswirkung", requiredMode = REQUIRED) val text: String,
        @field:Schema(description = "nur an diese Partner; leer = an alle aktiven Partner") val partnerIds: List<Long>? = null,
    )

    @Schema(description = "Eine versendete Ankündigung")
    data class AnnouncementDto(
        @field:Schema(requiredMode = REQUIRED) val id: Long,
        @field:Schema(requiredMode = REQUIRED) val start: Instant,
        @field:Schema(requiredMode = REQUIRED) val end: Instant,
        @field:Schema(requiredMode = REQUIRED) val subject: String,
        @field:Schema(requiredMode = REQUIRED) val text: String,
        @field:Schema(requiredMode = REQUIRED) val partnerIds: List<Long>,
        @field:Schema(description = "Partner, an deren technischen Kontakt die Mail ging", requiredMode = REQUIRED) val recipients: Int,
        @field:Schema(description = "z. B. weniger als 5 Werktage Vorlauf (NF-08), Partner ohne Kontaktadresse", requiredMode = REQUIRED) val warnings: List<String>,
        @field:Schema(requiredMode = REQUIRED) val createdAt: Instant,
        @field:Schema(requiredMode = REQUIRED) val createdBy: String,
    )

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Requires.PARTNERS_ADMIN)
    @Operation(
        summary = "Wartung ankündigen",
        description = "Schickt eine Mail an den technischen Kontakt jedes Partners (Kopie an OPENSPRI_NOTIFICATIONS_OPERATOR_EMAIL). Die Spezifikation verlangt " +
            "5 Werktage Vorlauf (11.5); eine kürzere Frist ist möglich, steht aber in `warnings`.",
    )
    fun announce(@RequestBody body: AnnouncementRequest): AnnouncementDto {
        val actor = currentActor()
        val announcement = service.announce(body.start, body.end, body.subject, body.text, body.partnerIds?.map(::PartnerId), actor.label)
        audit.record(actor, "maintenance.announce", "maintenance:${announcement.id}", "recipients=${announcement.recipients}")
        return toDto(announcement)
    }

    @GetMapping
    @PreAuthorize(Requires.PARTNERS_READ)
    @Operation(summary = "Bisherige Ankündigungen", description = "Neueste zuerst.")
    fun list(@RequestParam(defaultValue = "50") limit: Int): List<AnnouncementDto> = store.list(limit).map(::toDto)

    private fun toDto(a: MaintenanceAnnouncement) = AnnouncementDto(
        a.id!!, a.start, a.end, a.subject, a.text, a.partnerIds.map { it.value }, a.recipients, a.warnings, a.createdAt, a.createdBy,
    )
}
