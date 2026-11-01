package de.openspri.soap.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * Configuration of the S/PRI SOAP interface (prefix `openspri.soap`, environment `OPENSPRI_SOAP_*`).
 *
 * @property signingKeystore PKCS#12 file with our WS-Security key (RSA >= 3072). If not set, a temporary key
 *   is generated at startup - only for development; the setup wizard (M4) creates and stores the real key.
 * @property minimumPartnerKeyBits smallest RSA key accepted from partners; the AK accepts existing 2048-bit
 *   certificates until they expire (security document V3.0, 2.5)
 * @property maxRequestBytes upper limit for one SOAP request including attachments
 * @property publishedEndpointUrl public URL of our endpoint, written into the WSDL (`?wsdl`), e.g.
 *   https://spri.example.de/spri/v4
 */
@ConfigurationProperties("openspri.soap")
data class SpriSoapProperties(
    val signingKeystore: String? = null,
    val signingKeystorePassword: String? = null,
    val signingKeyAlias: String? = null,
    val minimumPartnerKeyBits: Int = 2048,
    val maxRequestBytes: Int = 30 * 1024 * 1024,
    val publishedEndpointUrl: String? = null,
    val client: Client = Client(),
) {
    data class Client(
        val connectTimeout: Duration = Duration.ofSeconds(10),
        val receiveTimeout: Duration = Duration.ofSeconds(30),
        val requireSignedResponse: Boolean = true,
        /** Egress proxy with a fixed outbound IP (AUFGABE chapter 7.6), environment SPRI_OUTBOUND_PROXY. */
        val outboundProxy: String? = null,
    ) {
        fun outboundProxyUri(): URI? = outboundProxy?.takeIf { it.isNotBlank() }?.let(URI::create)
    }
}
