package de.openspri.rest.auth

import de.openspri.auth.ApiToken
import de.openspri.auth.ApiTokenStore
import de.openspri.auth.ApiTokens
import de.openspri.auth.AuditLog
import de.openspri.auth.Role
import de.openspri.auth.Scope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/tokens", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "API-Tokens", description = "Long-Life-Tokens für externe Systeme (CRM, Workflow)")
class TokenController(private val tokens: ApiTokens, private val store: ApiTokenStore, private val audit: AuditLog) {

    @Schema(description = "Ein API-Token. Das Geheimnis wird nie wieder angezeigt.")
    data class TokenResponse(
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: Long,
        @field:Schema(example = "CRM Produktion", requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
        @field:Schema(description = "Öffentlicher Teil, erkennbar in Logs", example = "Ab3dE7fG", requiredMode = Schema.RequiredMode.REQUIRED) val prefix: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val role: Role,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val scopes: List<String>,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdBy: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
        val expiresAt: Instant?,
        val revokedAt: Instant?,
        val lastUsedAt: Instant?,
        val lastUsedIp: String?,
    )

    @Schema(description = "Neues Token")
    data class CreateTokenRequest(
        @field:Schema(example = "CRM Produktion", requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
        @field:Schema(description = "Rolle; bestimmt die maximal möglichen Berechtigungen", example = "INTEGRATION", requiredMode = Schema.RequiredMode.REQUIRED)
        val role: Role,
        @field:Schema(description = "Optional: Einschränkung auf einen Teil der Berechtigungen der Rolle", example = "[\"orders:read\"]")
        val scopes: List<String>? = null,
        @field:Schema(description = "Optional: Ablaufzeitpunkt; ohne Angabe gilt das Token bis zum Widerruf")
        val expiresAt: Instant? = null,
    )

    @Schema(description = "Das neue Token. `token` enthält das Geheimnis und wird nur in dieser Antwort angezeigt.")
    data class CreatedTokenResponse(
        @field:Schema(example = "spri_Ab3dE7fG_…", requiredMode = Schema.RequiredMode.REQUIRED) val token: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val details: TokenResponse,
    )

    @GetMapping
    @PreAuthorize(Requires.USERS_ADMIN)
    @Operation(summary = "Tokens auflisten")
    fun list(): List<TokenResponse> = store.list().map(::toResponse)

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Requires.USERS_ADMIN)
    @Operation(summary = "Token anlegen", description = "Das Geheimnis wird nur in der Antwort angezeigt; gespeichert wird ausschließlich sein Hash.")
    fun create(@RequestBody body: CreateTokenRequest): CreatedTokenResponse {
        val actor = currentActor()
        val issued = tokens.issue(body.name, body.role, body.scopes?.map(Scope::fromValue)?.toSet(), body.expiresAt, actor.id.takeIf { actor.kind.name == "USER" }, actor.label, Instant.now())
        audit.record(actor, "token.create", "token:${issued.token.prefix}", "role=${body.role}")
        return CreatedTokenResponse(issued.plainText, toResponse(issued.token))
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Requires.USERS_ADMIN)
    @Operation(summary = "Token widerrufen", description = "Wirkt sofort.")
    fun revoke(@PathVariable id: Long) {
        if (!store.revoke(id, Instant.now())) throw NoSuchElementException("Token $id nicht gefunden oder bereits widerrufen")
        audit.record(currentActor(), "token.revoke", "token:$id")
    }

    private fun toResponse(t: ApiToken) = TokenResponse(
        t.id!!, t.name, t.prefix, t.role, t.scopes.map { it.value }.sorted(), t.createdBy, t.createdAt, t.expiresAt, t.revokedAt, t.lastUsedAt, t.lastUsedIp,
    )
}
