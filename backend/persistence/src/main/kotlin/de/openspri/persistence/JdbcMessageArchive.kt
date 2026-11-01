package de.openspri.persistence

import de.openspri.domain.exchange.ArchivedExchange
import de.openspri.domain.exchange.Direction
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.partner.PartnerId
import de.openspri.domain.query.ArchiveQueries
import de.openspri.domain.exchange.InboundMessage
import de.openspri.domain.exchange.InboundStore
import de.openspri.domain.exchange.OutboundArchive
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.sql.Timestamp

/** Message archive (NF-09) and inbound queue. */
@Repository
class JdbcMessageArchive(private val jdbc: JdbcClient) : InboundStore, OutboundArchive, ArchiveQueries {

    @Transactional
    override fun storeAccepted(exchange: ArchivedExchange, message: InboundMessage): Long {
        val archiveId = insert(exchange)
        jdbc.sql(
            """INSERT INTO inbox_message (archive_id, partner_id, operation, external_order_number, sequence_key, payload)
               VALUES (:archive, :partner, :operation, :order, :key, :payload)""",
        )
            .param("key", "${message.partnerId.value}:" + (message.externalOrderNumber ?: "#archive-$archiveId"))
            .param("archive", archiveId).param("partner", message.partnerId.value)
            .param("operation", message.operation.wsdlName).param("order", message.externalOrderNumber)
            .param("payload", message.payload)
            .update()
        return archiveId
    }

    @Transactional
    override fun storeRejected(exchange: ArchivedExchange): Long = insert(exchange)

    @Transactional
    override fun store(exchange: ArchivedExchange): Long = insert(exchange)

    override fun get(id: Long): ArchivedExchange? =
        jdbc.sql("SELECT * FROM soap_archive WHERE id = :id AND tenant_id = :t").param("id", id).param("t", Tenant.DEFAULT).query { rs, _ ->
            ArchivedExchange(
                direction = Direction.valueOf(rs.getString("direction")),
                operation = rs.getString("operation")?.let(SoapOperation::fromWsdlName),
                partnerId = rs.getLong("partner_id").takeIf { !rs.wasNull() }?.let(::PartnerId),
                remoteAddress = rs.getString("remote_address"),
                startedAt = rs.getTimestamp("started_at").toInstant(),
                durationMillis = rs.getLong("duration_ms"),
                request = rs.getBytes("request"),
                response = rs.getBytes("response"),
                teqCode = rs.getString("teq_code")?.let(TeqCode::fromCode),
                rejectionReason = rs.getString("rejection_reason"),
                signerSubject = rs.getString("signer_subject"),
                signerSerial = rs.getString("signer_serial"),
                externalOrderNumber = rs.getString("external_order_number"),
                findings = rs.getString("findings"),
            )
        }.optional().orElse(null)

    private fun insert(exchange: ArchivedExchange): Long {
        val keyHolder = GeneratedKeyHolder()
        jdbc.sql(
            """INSERT INTO soap_archive (tenant_id, direction, operation, partner_id, remote_address, started_at, duration_ms,
                   teq_code, rejection_reason, findings, signer_subject, signer_serial, external_order_number, request, request_sha256, response)
               VALUES (:tenant, :direction, :operation, :partner, :remote, :started, :duration,
                   :teq, :reason, :findings, :subject, :serial, :order, :request, :sha, :response)""",
        )
            .param("tenant", Tenant.DEFAULT)
            .param("direction", exchange.direction.name)
            .param("operation", exchange.operation?.wsdlName)
            .param("partner", exchange.partnerId?.value)
            .param("remote", exchange.remoteAddress.take(500))
            .param("started", Timestamp.from(exchange.startedAt))
            .param("duration", exchange.durationMillis)
            .param("teq", exchange.teqCode?.code)
            .param("reason", exchange.rejectionReason?.take(2000))
            .param("findings", exchange.findings?.take(4000))
            .param("subject", exchange.signerSubject?.take(500))
            .param("serial", exchange.signerSerial)
            .param("order", exchange.externalOrderNumber?.take(20))
            .param("request", exchange.request)
            .param("sha", sha256(exchange.request))
            .param("response", exchange.response)
            .update(keyHolder, "id")
        return keyHolder.key!!.toLong()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
