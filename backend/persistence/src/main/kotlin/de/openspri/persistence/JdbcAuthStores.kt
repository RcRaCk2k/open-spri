package de.openspri.persistence

import de.openspri.auth.ApiToken
import de.openspri.auth.ApiTokenStore
import de.openspri.auth.AuditEntry
import de.openspri.auth.AuditFilter
import de.openspri.auth.AuditTrail
import de.openspri.auth.AuditLog
import de.openspri.auth.Actor
import de.openspri.auth.Role
import de.openspri.auth.Scope
import de.openspri.auth.User
import de.openspri.auth.UserStore
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class JdbcUserStore(private val jdbc: JdbcClient) : UserStore {

    override fun save(user: User): User {
        if (user.id == null) {
            val keys = GeneratedKeyHolder()
            jdbc.sql(
                """INSERT INTO app_user (tenant_id, username, display_name, role, password_hash, enabled, created_at)
                   VALUES (:t, :u, :d, :r, :h, :e, :c)""",
            ).param("t", Tenant.DEFAULT).param("u", user.username).param("d", user.displayName).param("r", user.role.name)
                .param("h", user.passwordHash).param("e", user.enabled).param("c", Timestamp.from(user.createdAt)).update(keys, "id")
            return user.copy(id = keys.key!!.toLong())
        }
        jdbc.sql("UPDATE app_user SET display_name = :d, role = :r, password_hash = :h, enabled = :e WHERE id = :id")
            .param("d", user.displayName).param("r", user.role.name).param("h", user.passwordHash).param("e", user.enabled)
            .param("id", user.id).update()
        return user
    }

    override fun findByUsername(username: String): User? =
        jdbc.sql("SELECT * FROM app_user WHERE tenant_id = :t AND username = :u").param("t", Tenant.DEFAULT).param("u", username)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun findById(id: Long): User? =
        jdbc.sql("SELECT * FROM app_user WHERE id = :id").param("id", id).query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun list(): List<User> =
        jdbc.sql("SELECT * FROM app_user WHERE tenant_id = :t ORDER BY username").param("t", Tenant.DEFAULT).query { rs, _ -> map(rs) }.list()

    override fun count(): Long =
        jdbc.sql("SELECT COUNT(*) FROM app_user WHERE tenant_id = :t").param("t", Tenant.DEFAULT).query(Long::class.java).single()

    override fun touchLogin(id: Long, at: Instant) {
        jdbc.sql("UPDATE app_user SET last_login_at = :at WHERE id = :id").param("at", Timestamp.from(at)).param("id", id).update()
    }

    private fun map(rs: ResultSet) = User(
        id = rs.getLong("id"),
        username = rs.getString("username"),
        displayName = rs.getString("display_name"),
        role = Role.valueOf(rs.getString("role")),
        passwordHash = rs.getString("password_hash"),
        enabled = rs.getBoolean("enabled"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        lastLoginAt = rs.getTimestamp("last_login_at")?.toInstant(),
    )
}

@Repository
class JdbcApiTokenStore(private val jdbc: JdbcClient) : ApiTokenStore {

    override fun save(token: ApiToken): ApiToken {
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """INSERT INTO api_token (tenant_id, name, prefix, secret_hash, role, scopes, owner_user_id, partner_id, created_by, created_at, expires_at)
               VALUES (:t, :n, :p, :h, :r, :s, :o, :partner, :cb, :c, :e)""",
        ).param("t", Tenant.DEFAULT).param("n", token.name).param("p", token.prefix).param("h", token.secretHash)
            .param("r", token.role.name).param("s", token.scopes.joinToString(",") { it.value }).param("o", token.ownerUserId).param("partner", token.partnerId)
            .param("cb", token.createdBy).param("c", Timestamp.from(token.createdAt)).param("e", token.expiresAt?.let(Timestamp::from))
            .update(keys, "id")
        return token.copy(id = keys.key!!.toLong())
    }

    override fun findByPrefix(prefix: String): ApiToken? =
        jdbc.sql("SELECT * FROM api_token WHERE prefix = :p").param("p", prefix).query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun list(): List<ApiToken> =
        jdbc.sql("SELECT * FROM api_token WHERE tenant_id = :t ORDER BY created_at DESC").param("t", Tenant.DEFAULT).query { rs, _ -> map(rs) }.list()

    override fun revoke(id: Long, at: Instant): Boolean =
        jdbc.sql("UPDATE api_token SET revoked_at = :at WHERE id = :id AND revoked_at IS NULL").param("at", Timestamp.from(at)).param("id", id).update() == 1

    override fun touch(id: Long, at: Instant, ip: String?) {
        // at most once a minute, to keep write load low for busy integrations
        jdbc.sql("UPDATE api_token SET last_used_at = :at, last_used_ip = :ip WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :limit)")
            .param("at", Timestamp.from(at)).param("ip", ip?.take(50)).param("id", id).param("limit", Timestamp.from(at.minusSeconds(60))).update()
    }

    private fun map(rs: ResultSet) = ApiToken(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        prefix = rs.getString("prefix"),
        secretHash = rs.getString("secret_hash"),
        role = Role.valueOf(rs.getString("role")),
        scopes = rs.getString("scopes").split(',').filter { it.isNotEmpty() }.map(Scope::fromValue).toSet(),
        ownerUserId = rs.getLong("owner_user_id").takeIf { !rs.wasNull() },
        createdBy = rs.getString("created_by"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
        revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
        lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
        lastUsedIp = rs.getString("last_used_ip"),
        partnerId = rs.getLong("partner_id").takeIf { !rs.wasNull() },
    )
}

@Repository
class JdbcAuditLog(private val jdbc: JdbcClient) : AuditLog, AuditTrail {

    override fun record(actor: Actor, action: String, target: String, details: String?) {
        jdbc.sql("INSERT INTO audit_log (tenant_id, at, actor, action, target, details) VALUES (:t, :at, :a, :ac, :tg, :d)")
            .param("t", Tenant.DEFAULT).param("at", Timestamp.from(Instant.now())).param("a", actor.label.take(300))
            .param("ac", action.take(100)).param("tg", target.take(300)).param("d", details?.take(2000)).update()
    }

    override fun search(filter: AuditFilter): List<AuditEntry> {
        val where = mutableListOf("tenant_id = :t")
        val params = mutableMapOf<String, Any>("t" to Tenant.DEFAULT, "l" to filter.limit.coerceIn(1, 500))
        filter.actor?.takeIf { it.isNotBlank() }?.let { where += "actor LIKE :actor ESCAPE '!'"; params["actor"] = "%" + like(it) + "%" }
        filter.action?.takeIf { it.isNotBlank() }?.let { where += "action LIKE :action ESCAPE '!'"; params["action"] = like(it) + "%" }
        filter.target?.takeIf { it.isNotBlank() }?.let { where += "target LIKE :target ESCAPE '!'"; params["target"] = "%" + like(it) + "%" }
        filter.from?.let { where += "at >= :from"; params["from"] = Timestamp.from(it) }
        filter.to?.let { where += "at < :to"; params["to"] = Timestamp.from(it) }
        filter.cursor?.let { where += "id < :cursor"; params["cursor"] = it }
        return jdbc.sql("SELECT id, at, actor, action, target, details FROM audit_log WHERE ${where.joinToString(" AND ")} ORDER BY id DESC LIMIT :l")
            .params(params)
            .query { rs, _ -> AuditEntry(rs.getLong("id"), rs.getTimestamp("at").toInstant(), rs.getString("actor"), rs.getString("action"), rs.getString("target"), rs.getString("details")) }
            .list()
    }

    private fun like(value: String) = value.replace("!", "!!").replace("%", "!%").replace("_", "!_")
}
