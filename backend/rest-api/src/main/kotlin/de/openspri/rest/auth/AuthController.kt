package de.openspri.rest.auth

import de.openspri.auth.Actor
import de.openspri.auth.AuditLog
import de.openspri.auth.AuthenticationModule
import de.openspri.auth.LocalUsers
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/auth", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Anmeldung", description = "Anmeldung der Web-Oberfläche und Informationen zur aktuellen Anmeldung")
class AuthController(
    private val users: LocalUsers,
    private val modules: List<AuthenticationModule>,
    private val contexts: SecurityContextRepository,
    private val audit: AuditLog,
) {
    @Schema(description = "Anmeldedaten")
    data class LoginRequest(
        @field:Schema(example = "admin", requiredMode = Schema.RequiredMode.REQUIRED) val username: String,
        @field:Schema(example = "ein-langes-passwort", requiredMode = Schema.RequiredMode.REQUIRED) val password: String,
    )

    @Schema(description = "Die angemeldete Person bzw. das Token")
    data class MeResponse(
        @field:Schema(description = "USER, TOKEN, SETUP", requiredMode = Schema.RequiredMode.REQUIRED) val kind: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
        @field:Schema(description = "Berechtigungen", requiredMode = Schema.RequiredMode.REQUIRED) val scopes: List<String>,
    )

    @Schema(description = "Ein verfügbares Anmeldeverfahren")
    data class ModuleResponse(
        @field:Schema(example = "local-users", requiredMode = Schema.RequiredMode.REQUIRED) val id: String,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val displayName: String,
        @field:Schema(description = "true, wenn das Verfahren eine Anmeldung in der Oberfläche anbietet", requiredMode = Schema.RequiredMode.REQUIRED)
        val interactiveLogin: Boolean,
    )

    @PostMapping("/login", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Anmelden (Web-Oberfläche)",
        description = "Prüft Benutzername und Passwort und legt eine Sitzung an (Cookie, SameSite=Strict). Externe Systeme verwenden statt dessen API-Tokens.",
    )
    fun login(@RequestBody body: LoginRequest, request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<MeResponse> {
        val user = users.authenticate(body.username, body.password, Instant.now())
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        request.getSession(true)
        request.changeSessionId() // protection against session fixation
        val actor = Actor(Actor.Kind.USER, user.id, user.username, user.role.scopes)
        val context = SecurityContextHolder.createEmptyContext().apply {
            authentication = UsernamePasswordAuthenticationToken.authenticated(actor, null, actor.scopes.map { it.authority })
        }
        SecurityContextHolder.setContext(context)
        contexts.saveContext(context, request, response)
        audit.record(actor, "login", "user:${user.username}")
        return ResponseEntity.ok(me(actor))
    }

    @PostMapping("/logout")
    @Operation(summary = "Abmelden", description = "Beendet die Sitzung der Web-Oberfläche.")
    fun logout(request: HttpServletRequest): ResponseEntity<Unit> {
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me")
    @Operation(summary = "Aktuelle Anmeldung", description = "Wer ist angemeldet und mit welchen Berechtigungen.")
    fun me(): MeResponse = me(currentActor())

    @GetMapping("/modules")
    @Operation(summary = "Anmeldeverfahren", description = "Die in dieser Instanz aktiven Anmeldeverfahren. Ohne Anmeldung aufrufbar.")
    fun modules(): List<ModuleResponse> = modules.map { ModuleResponse(it.id, it.displayName, it.interactiveLogin) }

    private fun me(actor: Actor) = MeResponse(actor.kind.name, actor.name, actor.scopes.map { it.value }.sorted())
}
