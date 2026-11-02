package de.openspri.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * A stored API token. The secret itself is never stored, only its SHA-256 hash (AUFGABE 7.5).
 * Token format: `spri_<prefix>_<secret>`; the prefix identifies the token and may be shown and logged.
 */
data class ApiToken(
    val id: Long?,
    val name: String,
    val prefix: String,
    val secretHash: String,
    val role: Role,
    val scopes: Set<Scope>,
    val ownerUserId: Long?,
    val createdBy: String,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val revokedAt: Instant? = null,
    val lastUsedAt: Instant? = null,
    val lastUsedIp: String? = null,
    /** Role PARTNER: the partner the token belongs to (Replikat download, online query). */
    val partnerId: Long? = null,
) {
    fun usable(now: Instant) = revokedAt == null && (expiresAt == null || expiresAt.isAfter(now))
}

interface ApiTokenStore {
    fun save(token: ApiToken): ApiToken
    fun findByPrefix(prefix: String): ApiToken?
    fun list(): List<ApiToken>
    fun revoke(id: Long, at: Instant): Boolean
    fun touch(id: Long, at: Instant, ip: String?)
}

/** Creating and checking tokens. */
class ApiTokens(private val store: ApiTokenStore, private val random: SecureRandom = SecureRandom()) {

    data class Issued(val token: ApiToken, val plainText: String)

    fun issue(
        name: String,
        role: Role,
        scopes: Set<Scope>?,
        expiresAt: Instant?,
        ownerUserId: Long?,
        createdBy: String,
        now: Instant,
        partnerId: Long? = null,
    ): Issued {
        val effective = scopes ?: role.scopes
        require(role.grantable.containsAll(effective)) { "Scopes ${effective - role.grantable} gehören nicht zur Rolle $role" }
        require(name.isNotBlank()) { "Ein Token braucht einen Namen" }
        require((role == Role.PARTNER) == (partnerId != null)) { "partnerId: nur und immer bei Rolle PARTNER" }
        val prefix = randomString(8)
        val secret = randomString(32)
        val token = store.save(ApiToken(null, name.trim(), prefix, hash(secret), role, effective, ownerUserId, createdBy, now, expiresAt, partnerId = partnerId))
        return Issued(token, "$PREFIX${prefix}_$secret")
    }

    /** Returns the token if [plainText] is valid and usable now. */
    fun verify(plainText: String, now: Instant): ApiToken? {
        if (!plainText.startsWith(PREFIX)) return null
        val parts = plainText.removePrefix(PREFIX).split('_', limit = 2)
        if (parts.size != 2) return null
        val token = store.findByPrefix(parts[0]) ?: return null
        val matches = MessageDigest.isEqual(token.secretHash.toByteArray(), hash(parts[1]).toByteArray())
        return token.takeIf { matches && it.usable(now) }
    }

    private fun randomString(bytes: Int): String {
        val buffer = ByteArray(bytes).also(random::nextBytes)
        // Base64url without padding; '_' is replaced so that the prefix separator stays unique
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer).replace('_', 'x').replace('-', 'y')
    }

    companion object {
        const val PREFIX = "spri_"

        fun hash(secret: String): String =
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
