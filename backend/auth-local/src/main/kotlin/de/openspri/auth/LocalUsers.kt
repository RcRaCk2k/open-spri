package de.openspri.auth

import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.time.Instant

data class User(
    val id: Long?,
    val username: String,
    val displayName: String,
    val role: Role,
    val passwordHash: String,
    val enabled: Boolean = true,
    val createdAt: Instant,
    val lastLoginAt: Instant? = null,
)

interface UserStore {
    fun save(user: User): User
    fun findByUsername(username: String): User?
    fun findById(id: Long): User?
    fun list(): List<User>
    fun count(): Long
    fun touchLogin(id: Long, at: Instant)
}

/**
 * Local users (module "local-users", AUFGABE 7.5). Passwords are hashed with Argon2id. The REST login
 * (POST /api/v1/auth/login) creates a session cookie for the web frontend.
 */
class LocalUsers(private val store: UserStore) : AuthenticationModule {
    override val id = "local-users"
    override val displayName = "Benutzername und Passwort"
    override val interactiveLogin = true

    private val encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

    /** Sessions are handled by the security configuration of the REST API; nothing to add here. */
    override fun configure(http: HttpSecurity) = Unit

    fun create(username: String, displayName: String, role: Role, password: String, now: Instant): User {
        validatePassword(password)
        require(USERNAME.matches(username)) { "Benutzername: 3–50 Zeichen, Buchstaben, Ziffern, Punkt, Bindestrich, Unterstrich" }
        require(store.findByUsername(username) == null) { "Benutzer $username existiert bereits" }
        return store.save(User(null, username, displayName.ifBlank { username }, role, encoder.encode(password)!!, createdAt = now))
    }

    /** Returns the user if the credentials are correct and the user is enabled. */
    fun authenticate(username: String, password: String, now: Instant): User? {
        val user = store.findByUsername(username)
        // compare against a dummy hash for unknown users, so that the response time does not reveal them
        val hash = user?.passwordHash ?: DUMMY_HASH
        val ok = encoder.matches(password, hash) && user != null && user.enabled
        if (ok) store.touchLogin(user.id!!, now)
        return user.takeIf { ok }
    }

    fun changePassword(user: User, newPassword: String): User {
        validatePassword(newPassword)
        return store.save(user.copy(passwordHash = encoder.encode(newPassword)!!))
    }

    private fun validatePassword(password: String) {
        require(password.length >= MIN_PASSWORD_LENGTH) { "Das Passwort muss mindestens $MIN_PASSWORD_LENGTH Zeichen lang sein" }
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 12
        private val USERNAME = Regex("""[A-Za-z0-9._-]{3,50}""")
        private val DUMMY_HASH: String = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("dummy-password-for-timing")!!
    }
}
