package de.openspri.persistence

import de.openspri.domain.partner.IpNetwork
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerAdministration
import de.openspri.domain.partner.PartnerCertificate
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
class JdbcPartnerDirectory(private val jdbc: JdbcClient) : PartnerDirectory, PartnerAdministration {

    private val certificateFactory = CertificateFactory.getInstance("X.509")

    override fun findBySourceAddress(address: InetAddress): List<Partner> =
        enabledPartners().filter { partner -> partner.allowedSourceNetworks.any { address in it } }

    override fun findById(id: PartnerId): Partner? = enabledPartners().firstOrNull { it.id == id }

    fun enabledPartners(): List<Partner> = partners(onlyEnabled = true)

    override fun list(): List<Partner> = partners(onlyEnabled = false)

    override fun get(id: PartnerId): Partner? = list().firstOrNull { it.id == id }

    override fun certificates(id: PartnerId): List<PartnerCertificate> =
        jdbc.sql("SELECT id, certificate FROM partner_certificate WHERE partner_id = :p ORDER BY id").param("p", id.value)
            .query { rs, _ -> PartnerCertificate(rs.getLong("id"), toCertificate(rs.getBytes("certificate"))) }.list()

    override fun create(partner: Partner): PartnerId =
        create(partner.name, partner.carrierCode, partner.signatureCertificates, partner.allowedSourceNetworks, partner.endpointUrl, partner.technicalContactEmail)
            .also { if (!partner.enabled || !partner.useOutboundProxy) update(partner.copy(id = it)) }

    @Transactional
    override fun update(partner: Partner) {
        jdbc.sql(
            """UPDATE partner SET name = :n, carrier_code = :c, endpoint_url = :u, technical_contact_email = :m, enabled = :e,
                   use_outbound_proxy = :x WHERE id = :id""",
        )
            .param("n", partner.name).param("c", partner.carrierCode).param("u", partner.endpointUrl).param("m", partner.technicalContactEmail)
            .param("e", partner.enabled).param("x", partner.useOutboundProxy).param("id", partner.id.value).update()
    }

    override fun addCertificate(id: PartnerId, certificate: X509Certificate): Long {
        val keys = org.springframework.jdbc.support.GeneratedKeyHolder()
        jdbc.sql(
            """INSERT INTO partner_certificate (partner_id, certificate, subject, issuer, serial_number, not_after)
               VALUES (:partner, :der, :subject, :issuer, :serial, :notAfter)""",
        ).param("partner", id.value).param("der", certificate.encoded).param("subject", certificate.subjectX500Principal.name)
            .param("issuer", certificate.issuerX500Principal.name).param("serial", certificate.serialNumber.toString())
            .param("notAfter", Timestamp(certificate.notAfter.time)).update(keys, "id")
        return keys.key!!.toLong()
    }

    override fun removeCertificate(id: PartnerId, certificateId: Long): Boolean =
        jdbc.sql("DELETE FROM partner_certificate WHERE id = :c AND partner_id = :p").param("c", certificateId).param("p", id.value).update() == 1

    @Transactional
    override fun replaceNetworks(id: PartnerId, networks: List<IpNetwork>) {
        jdbc.sql("DELETE FROM partner_source_network WHERE partner_id = :p").param("p", id.value).update()
        networks.distinct().forEach { network ->
            jdbc.sql("INSERT INTO partner_source_network (partner_id, cidr) VALUES (:p, :c)").param("p", id.value).param("c", network.toString()).update()
        }
    }

    private fun partners(onlyEnabled: Boolean): List<Partner> {
        val networks = jdbc.sql("SELECT partner_id, cidr FROM partner_source_network")
            .query { rs, _ -> rs.getLong("partner_id") to IpNetwork.parse(rs.getString("cidr")) }
            .list().groupBy({ it.first }, { it.second })
        val certificates = jdbc.sql("SELECT partner_id, certificate FROM partner_certificate")
            .query { rs, _ -> rs.getLong("partner_id") to toCertificate(rs.getBytes("certificate")) }
            .list().groupBy({ it.first }, { it.second })
        return jdbc.sql(
            "SELECT id, name, carrier_code, endpoint_url, technical_contact_email, enabled, use_outbound_proxy FROM partner WHERE tenant_id = :tenant" +
                (if (onlyEnabled) " AND enabled" else "") + " ORDER BY name",
        )
            .param("tenant", Tenant.DEFAULT)
            .query { rs, _ ->
                val id = rs.getLong("id")
                Partner(
                    id = PartnerId(id),
                    name = rs.getString("name"),
                    carrierCode = rs.getString("carrier_code"),
                    signatureCertificates = certificates[id].orEmpty(),
                    allowedSourceNetworks = networks[id].orEmpty(),
                    enabled = rs.getBoolean("enabled"),
                    endpointUrl = rs.getString("endpoint_url"),
                    technicalContactEmail = rs.getString("technical_contact_email"),
                    useOutboundProxy = rs.getBoolean("use_outbound_proxy"),
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
