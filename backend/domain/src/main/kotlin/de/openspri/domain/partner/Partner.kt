package de.openspri.domain.partner

import java.security.cert.X509Certificate

@JvmInline
value class PartnerId(val value: Long)

/**
 * A provider we exchange S/PRI messages with (AUFGABE chapter 5.9).
 *
 * @property carrierCode ITU Carrier Code (ICC) of the partner
 * @property signatureCertificates certificates accepted for WS-Security signatures of this partner;
 *   more than one during a certificate change (AUFGABE chapter 7.2)
 * @property allowedSourceNetworks outbound addresses of the partner that may call our endpoint
 *   (AK S&P security document V3.0, chapter 2.6)
 */
data class Partner(
    val id: PartnerId,
    val name: String,
    val carrierCode: String,
    val signatureCertificates: List<X509Certificate>,
    val allowedSourceNetworks: List<IpNetwork>,
    val enabled: Boolean = true,
    /** The partner's S/PRI endpoint (from its WSDL, specification 3.3). */
    val endpointUrl: String? = null,
    /** Receives the outage notification (specification 11.2.3.2) and the e-mail fallback. */
    val technicalContactEmail: String? = null,
    /** Send through the egress proxy (SPRI_OUTBOUND_PROXY) if one is configured; false = direct (AUFGABE 7.6). */
    val useOutboundProxy: Boolean = true,
)

/** A partner certificate as administered (with id, for removal during a certificate change). */
data class PartnerCertificate(val id: Long, val certificate: X509Certificate)

/** Partner administration (REST, M4). Read access for the SOAP layer is [PartnerDirectory]. */
interface PartnerAdministration {
    fun list(): List<Partner>
    fun get(id: PartnerId): Partner?
    fun certificates(id: PartnerId): List<PartnerCertificate>
    fun create(partner: Partner): PartnerId
    fun update(partner: Partner)
    fun addCertificate(id: PartnerId, certificate: X509Certificate): Long
    fun removeCertificate(id: PartnerId, certificateId: Long): Boolean
    fun replaceNetworks(id: PartnerId, networks: List<IpNetwork>)
}

/** Read access to the configured partners. */
interface PartnerDirectory {

    /** Enabled partners whose allowlist contains [address]. Usually zero or one. */
    fun findBySourceAddress(address: java.net.InetAddress): List<Partner>

    fun findById(id: PartnerId): Partner?
}
