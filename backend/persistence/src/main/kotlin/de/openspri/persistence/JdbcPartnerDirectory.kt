package de.openspri.persistence

import de.openspri.domain.partner.IpNetwork
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerDirectory
import de.openspri.domain.partner.PartnerId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.sql.Timestamp

/**
 * Partners from the database. The allowlist check runs on every S/PRI request; the number of partners is
 * small (tens), so all enabled partners are loaded and matched in memory.
 */
@Repository
class JdbcPartnerDirectory(private val jdbc: JdbcClient) : PartnerDirectory {

    private val certificateFactory = CertificateFactory.getInstance("X.509")

    override fun findBySourceAddress(address: InetAddress): List<Partner> =
        enabledPartners().filter { partner -> partner.allowedSourceNetworks.any { address in it } }

    override fun findById(id: PartnerId): Partner? = enabledPartners().firstOrNull { it.id == id }

    fun enabledPartners(): List<Partner> {
        val networks = jdbc.sql("SELECT partner_id, cidr FROM partner_source_network")
            .query { rs, _ -> rs.getLong("partner_id") to IpNetwork.parse(rs.getString("cidr")) }
            .list().groupBy({ it.first }, { it.second })
        val certificates = jdbc.sql("SELECT partner_id, certificate FROM partner_certificate")
            .query { rs, _ -> rs.getLong("partner_id") to toCertificate(rs.getBytes("certificate")) }
            .list().groupBy({ it.first }, { it.second })
        return jdbc.sql("SELECT id, name, carrier_code, endpoint_url, technical_contact_email FROM partner WHERE tenant_id = :tenant AND enabled")
            .param("tenant", Tenant.DEFAULT)
            .query { rs, _ ->
                val id = rs.getLong("id")
                Partner(
                    id = PartnerId(id),
                    name = rs.getString("name"),
                    carrierCode = rs.getString("carrier_code"),
                    signatureCertificates = certificates[id].orEmpty(),
                    allowedSourceNetworks = networks[id].orEmpty(),
                    endpointUrl = rs.getString("endpoint_url"),
                    technicalContactEmail = rs.getString("technical_contact_email"),
                )
            }.list()
    }

    /** Creates a partner. Used by tests now and by the partner REST API in M4. */
    @Transactional
    fun create(
        name: String,
        carrierCode: String,
        certificates: List<X509Certificate>,
        networks: List<IpNetwork>,
        endpointUrl: String? = null,
        technicalContactEmail: String? = null,
    ): PartnerId {
        val keyHolder = org.springframework.jdbc.support.GeneratedKeyHolder()
        jdbc.sql(
            "INSERT INTO partner (tenant_id, name, carrier_code, endpoint_url, technical_contact_email) VALUES (:tenant, :name, :code, :url, :mail)",
        )
            .param("tenant", Tenant.DEFAULT).param("name", name).param("code", carrierCode)
            .param("url", endpointUrl).param("mail", technicalContactEmail)
            .update(keyHolder, "id")
        val id = keyHolder.key!!.toLong()
        certificates.forEach { certificate ->
            jdbc.sql(
                """INSERT INTO partner_certificate (partner_id, certificate, subject, issuer, serial_number, not_after)
                   VALUES (:partner, :der, :subject, :issuer, :serial, :notAfter)""",
            )
                .param("partner", id).param("der", certificate.encoded)
                .param("subject", certificate.subjectX500Principal.name).param("issuer", certificate.issuerX500Principal.name)
                .param("serial", certificate.serialNumber.toString()).param("notAfter", Timestamp(certificate.notAfter.time))
                .update()
        }
        networks.forEach { network ->
            jdbc.sql("INSERT INTO partner_source_network (partner_id, cidr) VALUES (:partner, :cidr)")
                .param("partner", id).param("cidr", network.toString()).update()
        }
        return PartnerId(id)
    }

    private fun toCertificate(der: ByteArray) = certificateFactory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
}

/** v1 is single-tenant (D-12): all data belongs to tenant 1, created by migration V1. */
object Tenant {
    const val DEFAULT = 1L
}
