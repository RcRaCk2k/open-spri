package de.openspri.rest.availability

import de.openspri.auth.AuditLog
import de.openspri.domain.availability.AddressFilter
import de.openspri.domain.availability.AvailabilityQuery
import de.openspri.domain.availability.AvailabilitySearch
import de.openspri.domain.availability.AvailabilitySource
import de.openspri.domain.availability.AvailabilitySources
import de.openspri.domain.availability.PartnerLocations
import de.openspri.domain.availability.ReplikatImport
import de.openspri.domain.availability.ReplikatImports
import de.openspri.domain.availability.SourceKind
import de.openspri.domain.partner.PartnerAdministration
import de.openspri.domain.partner.PartnerId
import de.openspri.replikat.ReplikatImporter
import de.openspri.replikat.ReplikatJson
import de.openspri.replikat.ReplikatOrderTemplate
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import de.openspri.rest.order.enumValue
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.Instant
import java.time.LocalDate

@Schema(description = "Quelle für die Verfügbarkeit eines Partners (Auftraggeber)")
data class SourceDto(
    @field:Schema(accessMode = Schema.AccessMode.READ_ONLY) val id: Long? = null,
    @field:Schema(requiredMode = REQUIRED) val partnerId: Long,
    @field:Schema(description = "REPLIKAT (täglicher Download) oder ONLINE (Online-Abfrage an eine open-spri-Instanz)", requiredMode = REQUIRED) val kind: String,
    @field:Schema(description = "REPLIKAT: Download-Verzeichnis, z. B. https://spri.partner.example/replikat/; ONLINE: Basis-URL der Instanz", requiredMode = REQUIRED)
    val url: String,
    @field:Schema(description = "Benutzer für Basic-Auth; leer = das Geheimnis wird als Bearer-Token gesendet") val username: String? = null,
    @field:Schema(description = "Passwort bzw. Token; nur schreiben, wird nie zurückgegeben", accessMode = Schema.AccessMode.WRITE_ONLY) val secret: String? = null,
    @field:Schema(description = "ist ein Geheimnis hinterlegt", accessMode = Schema.AccessMode.READ_ONLY) val hasSecret: Boolean = false,
    @field:Schema(description = "REPLIKAT: Deltareplikat anwenden, wenn der Vortag importiert ist") val delta: Boolean = false,
    val enabled: Boolean = true,
    @field:Schema(description = "importierte Standorte (REPLIKAT)", accessMode = Schema.AccessMode.READ_ONLY) val locations: Int? = null,
)

@Schema(description = "Ein Importlauf eines Replikats")
data class ImportRunDto(
    @field:Schema(requiredMode = REQUIRED) val id: Long,
    @field:Schema(description = "OK oder FAILED", requiredMode = REQUIRED) val status: String,
    @field:Schema(requiredMode = REQUIRED) val startedAt: Instant,
    @field:Schema(requiredMode = REQUIRED) val finishedAt: Instant,
    val fileName: String?,
    val replikatDate: LocalDate?,
    @field:Schema(requiredMode = REQUIRED) val delta: Boolean,
    @field:Schema(requiredMode = REQUIRED) val records: Int,
    @field:Schema(requiredMode = REQUIRED) val added: Int,
    @field:Schema(requiredMode = REQUIRED) val changed: Int,
    @field:Schema(requiredMode = REQUIRED) val deleted: Int,
    @field:Schema(requiredMode = REQUIRED) val warnings: List<String>,
    val error: String?,
) {
    companion object {
        fun of(r: ReplikatImport) = ImportRunDto(
            r.id!!, r.status.name, r.startedAt, r.finishedAt, r.fileName, r.replikatDate, r.delta, r.records, r.added, r.changed, r.deleted, r.warnings, r.error,
        )
    }
}

@Schema(description = "Ein Treffer der Verfügbarkeitssuche")
data class HitDto(
    @field:Schema(requiredMode = REQUIRED) val partnerId: Long,
    @field:Schema(requiredMode = REQUIRED) val partnerName: String,
    @field:Schema(description = "REPLIKAT oder ONLINE", requiredMode = REQUIRED) val source: String,
    @field:Schema(description = "Datensatz in Replikat-Feldnamen: Produkte (pl), Ausbaustatus, Bereitstellungszeit, Eigentümerdaten …", requiredMode = REQUIRED)
    val ds: JsonNode,
)

@Schema(description = "Ergebnis der Suche; Partner, die nicht antworteten, stehen in `errors`")
data class SearchResultDto(
    @field:Schema(requiredMode = REQUIRED) val hits: List<HitDto>,
    @field:Schema(description = "Partner-ID → Fehler", requiredMode = REQUIRED) val errors: Map<Long, String>,
)

@Schema(description = "Treffer, aus dem ein NEU-Auftrag werden soll")
data class OrderTemplateRequest(
    @field:Schema(requiredMode = REQUIRED) val partnerId: Long,
    @field:Schema(description = "der Datensatz aus der Suche (`ds`)", requiredMode = REQUIRED) val ds: JsonNode,
    @field:Schema(description = "gewünschte Produkte; das erste wird Produkt der Auftragsposition, weitere werden Positionen. Leer = erstes Produkt") val products: List<String> = emptyList(),
    @field:Schema(description = "HomeID, falls der Standort mehrere bedient") val hid: String? = null,
)

@Schema(description = "Vorbefüllter NEU-Auftrag (Replikat 1.7 Kap. 4); vor `POST /orders` zu ergänzen")
data class OrderTemplateDto(
    @field:Schema(requiredMode = REQUIRED) val partnerId: Long,
    @field:Schema(description = "`auftrag` wie bei POST /orders, unvollständig", requiredMode = REQUIRED) val auftrag: JsonNode,
    @field:Schema(description = "Felder, die der Auftraggeber ergänzen muss", requiredMode = REQUIRED) val missing: List<String>,
    @field:Schema(description = "Hinweise aus dem Replikat, z. B. Konnektivitätsauftrag oder Eigentümerdaten", requiredMode = REQUIRED) val hints: List<String>,
)

/** Availability of the partners as Auftraggeber (AUFGABE 5.4): sources, import, search across partners, order template. */
@RestController
@Tag(name = "Verfügbarkeit", description = "Eigene Standorte, Replikat, Online-Abfrage und Suche bei Partnern (AUFGABE 5.4)")
class PartnerAvailabilityController(
    private val sources: AvailabilitySources,
    private val imports: ReplikatImports,
    private val locations: PartnerLocations,
    private val importer: ReplikatImporter,
    private val search: AvailabilitySearch,
    private val partners: PartnerAdministration,
    private val secrets: AvailabilityConfiguration.SourceSecrets,
    private val audit: AuditLog,
) {
    @GetMapping("/api/v1/replikat/sources", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.AVAILABILITY_READ)
    @Operation(summary = "Quellen für die Verfügbarkeit der Partner")
    fun sources(): List<SourceDto> = sources.list().map(::toDto)

    @PostMapping("/api/v1/replikat/sources", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Requires.AVAILABILITY_ADMIN)
    @Operation(
        summary = "Quelle anlegen",
        description = "REPLIKAT: täglicher Import um 07:00 (`OPENSPRI_REPLIKAT_IMPORT_CRON`) mit MD5- und XSD-Prüfung. ONLINE: Suche fragt die Instanz des Partners. " +
            "Das Geheimnis wird mit OPENSPRI_MASTER_KEY verschlüsselt gespeichert.",
    )
    fun create(@RequestBody body: SourceDto): SourceDto = save(null, body)

    @PutMapping("/api/v1/replikat/sources/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.AVAILABILITY_ADMIN)
    @Operation(summary = "Quelle ändern", description = "Ohne `secret` bleibt das hinterlegte Geheimnis.")
    fun update(@PathVariable id: Long, @RequestBody body: SourceDto): SourceDto = save(id, body)

    @DeleteMapping("/api/v1/replikat/sources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Requires.AVAILABILITY_ADMIN)
    @Operation(summary = "Quelle löschen")
    fun delete(@PathVariable id: Long) {
        if (!sources.delete(id)) throw NoSuchElementException("Quelle $id existiert nicht")
        audit.record(currentActor(), "availability.source.delete", "source:$id", null)
    }

    @PostMapping("/api/v1/replikat/sources/{id}/run", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.AVAILABILITY_ADMIN)
    @Operation(summary = "Replikat jetzt importieren", description = "Metadatei von heute, sonst von gestern; Fehler stehen im Ergebnis (status FAILED).")
    fun run(@PathVariable id: Long): ImportRunDto {
        val source = sources.get(id) ?: throw NoSuchElementException("Quelle $id existiert nicht")
        require(source.kind == SourceKind.REPLIKAT) { "Nur eine Replikat-Quelle wird importiert" }
        val partner = partners.get(source.partnerId) ?: throw NoSuchElementException("Partner ${source.partnerId.value} existiert nicht")
        val result = importer.run(partner, secrets.plain(source))
        audit.record(currentActor(), "availability.source.import", "source:$id", "${result.status} records=${result.records}")
        return ImportRunDto.of(result)
    }

    @GetMapping("/api/v1/replikat/sources/{id}/imports", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.AVAILABILITY_READ)
    @Operation(summary = "Importläufe einer Quelle", description = "Neueste zuerst.")
    fun imports(@PathVariable id: Long, @RequestParam(defaultValue = "30") limit: Int): List<ImportRunDto> = imports.list(id, limit).map(ImportRunDto::of)

    @GetMapping("/api/v1/availability/search", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.AVAILABILITY_READ)
    @Operation(
        summary = "Verfügbarkeit bei den Partnern suchen",
        description = "Über alle Partner mit aktiver Quelle (Replikat lokal oder Online-Abfrage). Adresse (`s`, `o` als Präfix), HomeID, Adress-ID oder " +
            "Koordinaten (`lat`, `long`, `radius` in Metern, nur Replikat).",
    )
    fun search(
        @RequestParam(required = false) plz: String?,
        @RequestParam(required = false) o: String?,
        @RequestParam(required = false) s: String?,
        @RequestParam(required = false) h: String?,
        @RequestParam(required = false) hz: String?,
        @RequestParam(required = false) aid: String?,
        @RequestParam(required = false) hid: String?,
        @RequestParam(required = false) lat: Double?,
        @RequestParam(required = false) long: Double?,
        @Parameter(description = "Umkreis in Metern (Standard 50)") @RequestParam(defaultValue = "50") radius: Int,
        @Parameter(description = "nur bei diesem Partner") @RequestParam(required = false) partnerId: Long?,
    ): SearchResultDto {
        require(radius in 1..5000) { "radius: 1 bis 5000 Meter" }
        val result = search.search(
            AvailabilityQuery(AddressFilter(plz, o, s, h, hz, hid, aid, limit = 100), lat, long, radius),
            partnerId?.let(::PartnerId),
        )
        return SearchResultDto(
            result.hits.map { HitDto(it.partnerId.value, it.partnerName, it.kind.name, ReplikatJson.toJson(it.location)) },
            result.errors.mapKeys { it.key.value },
        )
    }

    @PostMapping("/api/v1/availability/order-template", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.AVAILABILITY_READ)
    @Operation(
        summary = "Treffer in einen NEU-Auftrag übernehmen",
        description = "Vorbefüllt nach Replikat 1.7 Kap. 4: Adresse (StandortA), HomeID (Vormieter), Produkt und weitere Produkte als Positionen, Zeitfenster, " +
            "bei Konnektivitätsauftrag die Position „Konnektivität“. `missing` nennt, was vor POST /orders zu ergänzen ist.",
    )
    fun orderTemplate(@RequestBody body: OrderTemplateRequest): OrderTemplateDto {
        partners.get(PartnerId(body.partnerId)) ?: throw NoSuchElementException("Partner ${body.partnerId} existiert nicht")
        val template = ReplikatOrderTemplate.of(ReplikatJson.fromJson(body.ds, "$.ds"), body.products, body.hid)
        return OrderTemplateDto(body.partnerId, template.auftrag, template.missing, template.hints)
    }

    private fun save(id: Long?, body: SourceDto): SourceDto {
        val partnerId = PartnerId(body.partnerId)
        partners.get(partnerId) ?: throw NoSuchElementException("Partner ${body.partnerId} existiert nicht")
        val uri = runCatching { URI(body.url.trim()) }.getOrNull()
        require(uri != null && (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()) { "url: http(s)-URL erwartet" }
        val existing = id?.let { sources.get(it) ?: throw NoSuchElementException("Quelle $it existiert nicht") }
        val source = AvailabilitySource(
            id, partnerId, enumValue(body.kind, "kind"), uri.toString(), body.username?.trim()?.ifEmpty { null },
            body.secret?.let(secrets::encrypt) ?: existing?.secret, body.delta, body.enabled,
        )
        val saved = sources.save(source)
        audit.record(currentActor(), "availability.source.save", "source:${saved.id}", "partner=${body.partnerId} kind=${saved.kind}")
        return toDto(saved)
    }

    private fun toDto(s: AvailabilitySource) = SourceDto(
        s.id, s.partnerId.value, s.kind.name, s.url, s.username, null, s.secret != null, s.delta, s.enabled,
        locations.count(s.partnerId).takeIf { s.kind == SourceKind.REPLIKAT },
    )
}
