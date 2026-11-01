package de.openspri.rest.auth

import de.openspri.auth.AuditLog
import de.openspri.auth.LocalUsers
import de.openspri.auth.Role
import de.openspri.auth.User
import de.openspri.auth.UserStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/users", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Benutzer", description = "Lokale Benutzer der Web-Oberfläche")
class UserController(private val users: LocalUsers, private val store: UserStore, private val audit: AuditLog) {

    @Schema(description = "Ein Benutzer")
    data class UserResponse(
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: Long,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val username: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val displayName: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val role: Role,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val enabled: Boolean,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
        val lastLoginAt: Instant?,
    )

    @Schema(description = "Neuer Benutzer")
    data class CreateUserRequest(
        @field:Schema(example = "m.muster", requiredMode = Schema.RequiredMode.REQUIRED) val username: String,
        @field:Schema(example = "Max Muster", requiredMode = Schema.RequiredMode.REQUIRED) val displayName: String,
        @field:Schema(example = "OPERATOR_LE", requiredMode = Schema.RequiredMode.REQUIRED) val role: Role,
        @field:Schema(description = "mindestens 12 Zeichen", requiredMode = Schema.RequiredMode.REQUIRED) val password: String,
    )

    @Schema(description = "Änderungen; nicht angegebene Felder bleiben unverändert")
    data class UpdateUserRequest(
        val displayName: String? = null,
        val role: Role? = null,
        val enabled: Boolean? = null,
        @field:Schema(description = "neues Passwort, mindestens 12 Zeichen") val password: String? = null,
    )

    @GetMapping
    @PreAuthorize(Requires.USERS_ADMIN)
    @Operation(summary = "Benutzer auflisten")
    fun list(): List<UserResponse> = store.list().map(::toResponse)

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Requires.USERS_ADMIN)
    @Operation(summary = "Benutzer anlegen")
    fun create(@RequestBody body: CreateUserRequest): UserResponse {
        val user = users.create(body.username, body.displayName, body.role, body.password, Instant.now())
        audit.record(currentActor(), "user.create", "user:${user.username}", "role=${user.role}")
        return toResponse(user)
    }

    @PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize(Requires.USERS_ADMIN)
    @Operation(summary = "Benutzer ändern", description = "Name, Rolle, Sperre oder Passwort.")
    fun update(@PathVariable id: Long, @RequestBody body: UpdateUserRequest): UserResponse {
        var user = store.findById(id) ?: throw NoSuchElementException("Benutzer $id nicht gefunden")
        user = store.save(user.copy(displayName = body.displayName ?: user.displayName, role = body.role ?: user.role, enabled = body.enabled ?: user.enabled))
        body.password?.let { user = users.changePassword(user, it) }
        audit.record(currentActor(), "user.update", "user:${user.username}", listOfNotNull(body.role?.let { "role=$it" }, body.enabled?.let { "enabled=$it" }, body.password?.let { "password changed" }).joinToString())
        return toResponse(user)
    }

    private fun toResponse(u: User) = UserResponse(u.id!!, u.username, u.displayName, u.role, u.enabled, u.createdAt, u.lastLoginAt)
}
