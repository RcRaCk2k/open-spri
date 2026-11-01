package de.openspri.persistence

import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageCodeStore
import de.openspri.domain.message.MessageCodeTableParser
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

/** Versions of the message code table (Anlage 5); exactly one is active. */
@Repository
class JdbcMessageCodeTables(private val jdbc: JdbcClient) : MessageCodeStore {

    /** Imports [content] (AK export or neutral CSV) and activates it. An already known version is only activated. */
    @Transactional
    override fun importAndActivate(content: ByteArray, source: String): MessageCodeCatalog {
        val catalog = MessageCodeTableParser.parse(content, source)
        val existing = jdbc.sql("SELECT id FROM message_code_table WHERE tenant_id = :t AND sha256 = :s")
            .param("t", Tenant.DEFAULT).param("s", catalog.sha256).query(Long::class.java).optional().orElse(null)
        jdbc.sql("UPDATE message_code_table SET active = FALSE WHERE tenant_id = :t").param("t", Tenant.DEFAULT).update()
        if (existing != null) {
            jdbc.sql("UPDATE message_code_table SET active = TRUE WHERE id = :id").param("id", existing).update()
        } else {
            jdbc.sql(
                """INSERT INTO message_code_table (tenant_id, source, sha256, code_count, content, active, imported_at)
                   VALUES (:t, :source, :sha, :count, :content, TRUE, :at)""",
            ).param("t", Tenant.DEFAULT).param("source", source.take(300)).param("sha", catalog.sha256)
                .param("count", catalog.codes.size).param("content", content).param("at", Timestamp.from(Instant.now())).update()
        }
        return catalog
    }

    override fun active(): MessageCodeCatalog? =
        jdbc.sql("SELECT source, content FROM message_code_table WHERE tenant_id = :t AND active")
            .param("t", Tenant.DEFAULT)
            .query { rs, _ -> MessageCodeTableParser.parse(rs.getBytes("content"), rs.getString("source")) }
            .optional().orElse(null)
}
