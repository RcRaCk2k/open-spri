package de.openspri.rest.event

import de.openspri.auth.AuditLog
import de.openspri.domain.event.EventFeed
import de.openspri.domain.event.EventType
import de.openspri.domain.event.WebhookStore
import de.openspri.domain.event.WebhookSubscription
import de.openspri.rest.auth.Requires
import de.openspri.rest.auth.currentActor
import de.openspri.soap.config.MasterKey
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import io.swagger.v3.oas.annotations.tags.Tag
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

@Schema(description = "Ein Webhook-Empfänger")
data class WebhookDto(
    @field:Schema(requiredMode = REQUIRED) val id: Long,
    @field:Schema(example = "https://crm.example.invalid/hooks/open-spri", requiredMode = REQUIRED) val url: String,
    @field:Schema(description = "Ereignistypen; leer = alle", requiredMode = REQUIRED) val types: List<String>,
    @field:Schema(requiredMode = REQUIRED) val enabled: Boolean,
    val description: String?,
    @field:Schema(description = "Id des zuletzt zugestellten Ereignisses", requiredMode = REQUIRED) val lastEventId: Long,
    @field:Schema(description = "Fehlversuche in Folge", requiredMode = REQUIRED) val failures: Int,
    @field:Schema(requiredMode = REQUIRED) val nextAttemptAt: Instant,
    val lastError: String?,
    val lastSuccessAt: Instant?,
    @field:Schema(requiredMode = REQUIRED) val createdAt: Instant,
) {
    companion object {
        fun of(w: WebhookSubscription) = WebhookDto(
            w.id!!, w.url, w.types.map { it.wireName }.sorted(), w.enabled, w.description, w.lastEventId, w.failures, w.nextAttemptAt,
            w.lastError, w.lastSuccessAt, w.createdAt,
        )
    }
}

@Schema(description = "Neuer Webhook-Empfänger oder Änderung")
data class WebhookRequest(
    @field:Schema(description = "http(s)-URL des Empfängers", requiredMode = REQUIRED) val url: String,
    @field:Schema(description = "Ereignistypen; leer = alle") val types: List<String> = emptyList(),
    val enabled: Boolean = true,
    val description: String? = null,
)

@Schema(description = "Angelegter Webhook mit Geheimnis. Das Geheimnis wird nur dieses eine Mal angezeigt.")
data class CreatedWebhookDto(
    @field:Schema(requiredMode = REQUIRED) val webhook: WebhookDto,
    @field:Schema(description = "HMAC-SHA256-Schlüssel für `X-OpenSpri-Signature`", example = "whsec_…", requiredMode = REQUIRED) val secret: String,
)

@RestController
@RequestMapping("/api/v1/webhooks", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Ereignisse")
class WebhookController(
    private val store: WebhookStore,
    private val feed: EventFeed,
    private val masterKey: MasterKey,
    private val audit: AuditLog,
) {
    private val random = SecureRandom()

    @GetMapping
    @PreAuthorize(Requires.WEBHOOKS_ADMIN)
    @Operation(summary = "Webhooks auflisten")
    fun list(): List<WebhookDto> = store.list().map(WebhookDto::of)

    @GetMapping("/{id}")
    @PreAuthorize(Requires.WEBHOOKS_ADMIN)
    @Operation(summary = "Webhook lesen")
    fun get(@PathVariable id: Long): WebhookDto = WebhookDto.of(find(id))

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Requires.WEBHOOKS_ADMIN)
    @Operation(
        summary = "Webhook anlegen",
        description = "Der Empfänger erhält alle Ereignisse **ab jetzt**, ältere liest er über GET /events. Das Geheimnis wird " +
            "verschlüsselt gespeichert und nur in dieser Antwort angezeigt. Braucht `OPENSPRI_MASTER_KEY`.",
    )
    fun create(@RequestBody body: WebhookRequest): CreatedWebhookDto {
        val encryption = masterKey.encryption ?: throw IllegalStateException("Webhooks brauchen OPENSPRI_MASTER_KEY (Verschlüsselung des Geheimnisses)")
        val url = checkedUrl(body.url)
        val secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val now = Instant.now()
        val created = store.create(
            WebhookSubscription(
                id = null, url = url, encryptedSecret = encryption.encrypt(secret.toByteArray()), types = types(body.types),
                enabled = body.enabled, description = body.description?.take(200), lastEventId = feed.latestId(), nextAttemptAt = now, createdAt = now,
            ),
        )
        audit.record(currentActor(), "webhook.create", "webhook:${created.id}", url)
        return CreatedWebhookDto(WebhookDto.of(created), secret)
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.WEBHOOKS_ADMIN)
    @Operation(summary = "Webhook ändern", description = "Das Geheimnis bleibt; für ein neues Geheimnis den Webhook neu anlegen.")
    fun update(@PathVariable id: Long, @RequestBody body: WebhookRequest): WebhookDto {
        find(id)
        store.update(id, checkedUrl(body.url), types(body.types), body.enabled, body.description?.take(200))
        audit.record(currentActor(), "webhook.update", "webhook:$id")
        return WebhookDto.of(find(id))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Requires.WEBHOOKS_ADMIN)
    @Operation(summary = "Webhook löschen")
    fun delete(@PathVariable id: Long): ResponseEntity<Unit> {
        if (!store.delete(id)) throw NoSuchElementException("Webhook $id existiert nicht")
        audit.record(currentActor(), "webhook.delete", "webhook:$id")
        return ResponseEntity.noContent().build()
    }

    private fun find(id: Long) = store.get(id) ?: throw NoSuchElementException("Webhook $id existiert nicht")

    private fun types(names: List<String>) = names.map(EventType::fromWireName).toSet()

    private fun checkedUrl(url: String): String {
        val uri = try { URI(url.trim()) } catch (_: Exception) { throw IllegalArgumentException("url ist keine gültige URL") }
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "url: nur http(s) mit Hostnamen" }
        require(uri.userInfo == null) { "url: Zugangsdaten gehören nicht in die URL" }
        require(url.length <= 1000) { "url: höchstens 1000 Zeichen" }
        return uri.toString()
    }
}
