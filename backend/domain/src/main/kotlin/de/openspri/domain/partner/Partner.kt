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
)

/** Read access to the configured partners. */
interface PartnerDirectory {

    /** Enabled partners whose allowlist contains [address]. Usually zero or one. */
    fun findBySourceAddress(address: java.net.InetAddress): List<Partner>
}
