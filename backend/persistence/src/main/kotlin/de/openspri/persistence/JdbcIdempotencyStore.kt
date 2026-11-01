package de.openspri.persistence

import de.openspri.domain.event.IdempotencyClaim
import de.openspri.domain.event.IdempotencyStore
import de.openspri.domain.event.StoredResponse
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** Idempotency keys on table idempotency_key; the unique key (actor, key) decides between parallel requests. */
@Repository
class JdbcIdempotencyStore(private val jdbc: JdbcClient) : IdempotencyStore {

    override fun claim(actor: String, key: String, requestSha256: String, now: Instant): IdempotencyClaim {
        try {
            val keys = GeneratedKeyHolder()
            jdbc.sql("INSERT INTO idempotency_key (actor, idem_key, request_sha256, created_at) VALUES (:a, :k, :h, :now)")
                .param("a", actor).param("k", key).param("h", requestSha256).param("now", Timestamp.from(now))
                .update(keys, "id")
            return IdempotencyClaim.Claimed(keys.key!!.toLong())
        } catch (_: DuplicateKeyException) {
            return jdbc.sql("SELECT request_sha256, status, response, content_type FROM idempotency_key WHERE actor = :a AND idem_key = :k")
                .param("a", actor).param("k", key)
                .query { rs, _ ->
                    val status = rs.getInt("status").takeIf { !rs.wasNull() }
                    when {
                        rs.getString("request_sha256") != requestSha256 -> IdempotencyClaim.Mismatch
                        status == null -> IdempotencyClaim.InProgress
                        else -> IdempotencyClaim.Replay(StoredResponse(status, rs.getBytes("response") ?: ByteArray(0), rs.getString("content_type")))
                    }
                }.optional().orElse(IdempotencyClaim.InProgress)
        }
    }

    override fun complete(id: Long, response: StoredResponse) {
        jdbc.sql("UPDATE idempotency_key SET status = :s, response = :r, content_type = :c WHERE id = :id")
            .param("s", response.status).param("r", response.body).param("c", response.contentType).param("id", id).update()
    }

    override fun release(id: Long) {
        jdbc.sql("DELETE FROM idempotency_key WHERE id = :id").param("id", id).update()
    }

    override fun purgeBefore(time: Instant): Int =
        jdbc.sql("DELETE FROM idempotency_key WHERE created_at < :t").param("t", Timestamp.from(time)).update()
}
