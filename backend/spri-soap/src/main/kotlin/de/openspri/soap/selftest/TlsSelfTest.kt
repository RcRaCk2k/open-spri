package de.openspri.soap.selftest

import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** One check of a self-test; [warning] means "works, but act soon" (e.g. certificate expires in a few days). */
data class SelfTestCheck(val id: String, val ok: Boolean, val detail: String, val warning: Boolean = false)

/** Result of the TLS self-test of our public endpoint (AUFGABE 7.4). */
data class TlsSelfTestResult(
    val url: String,
    val protocol: String?,
    val subject: String?,
    val issuer: String?,
    val notAfter: Instant?,
    val keyType: String?,
    val keyBits: Int?,
    val checks: List<SelfTestCheck>,
) {
    val ok: Boolean get() = checks.all { it.ok }
}

/**
 * Calls our own public HTTPS address like a partner would and checks what the AK security document V3.0
 * (chapter 2.4) requires of the TLS certificate:
 *
 * - TLS 1.2 or newer
 * - chain trusted by the public CAs (system trust store), not self-signed
 * - certificate valid for the host name
 * - key ECC ≥ 256 bit or RSA ≥ 3072 bit, signature not SHA-1/MD5
 * - issuer on the whitelist, if one is configured ([trustedIssuers], substrings of the issuer DN)
 * - remaining validity; warning below [warnBefore]
 */
class TlsSelfTest(
    private val trustedIssuers: List<String> = emptyList(),
    private val warnBefore: Duration = Duration.ofDays(14),
    private val timeout: Duration = Duration.ofSeconds(10),
    private val clock: Clock = Clock.systemUTC(),
) {

    fun run(url: URI): TlsSelfTestResult {
        require(url.scheme == "https") { "Die öffentliche Adresse muss mit https:// beginnen: $url" }
        val host = url.host ?: throw IllegalArgumentException("Kein Hostname in $url")
        val port = if (url.port > 0) url.port else 443
        val (protocol, chain) = try {
            handshake(host, port)
        } catch (e: Exception) {
            return TlsSelfTestResult(
                url.toString(), null, null, null, null, null, null,
                listOf(SelfTestCheck("connect", false, "TLS-Verbindung zu $host:$port fehlgeschlagen: ${e.message}")),
            )
        }
        return evaluate(url.toString(), host, protocol, chain, trusted(chain))
    }

    /** The checks on an already received chain; separated from the network for tests. */
    fun evaluate(url: String, host: String, protocol: String, chain: List<X509Certificate>, trustedBySystem: Boolean): TlsSelfTestResult {
        val leaf = chain.first()
        val (keyType, keyBits) = when (val key = leaf.publicKey) {
            is RSAPublicKey -> "RSA" to key.modulus.bitLength()
            is ECPublicKey -> "EC" to key.params.order.bitLength()
            else -> key.algorithm to 0
        }
        val now = clock.instant()
        val daysLeft = Duration.between(now, leaf.notAfter.toInstant()).toDays()
        val selfSigned = leaf.subjectX500Principal == leaf.issuerX500Principal
        val issuer = leaf.issuerX500Principal.name
        val checks = buildList {
            add(SelfTestCheck("protocol", protocol in setOf("TLSv1.2", "TLSv1.3"), "ausgehandelt: $protocol (mindestens TLS 1.2)"))
            add(SelfTestCheck("selfSigned", !selfSigned, if (selfSigned) "selbstsigniert, z. B. das Übergangszertifikat des Proxys; Partner lehnen es ab" else "von einer CA ausgestellt"))
            add(SelfTestCheck("trust", trustedBySystem, if (trustedBySystem) "Kette ist vertrauenswürdig (öffentliche CAs)" else "Kette ist nicht vertrauenswürdig (fehlendes Zwischenzertifikat oder unbekannte CA)"))
            add(SelfTestCheck("hostname", matchesHost(leaf, host), "Zertifikat gilt für $host"))
            val keyOk = (keyType == "EC" && keyBits >= 256) || (keyType == "RSA" && keyBits >= 3072)
            add(SelfTestCheck("key", keyOk, "$keyType $keyBits Bit (gefordert: ECC ≥ 256 oder RSA ≥ 3072)"))
            val weak = leaf.sigAlgName.uppercase().let { "SHA1" in it || "MD5" in it }
            add(SelfTestCheck("signature", !weak, "Signaturalgorithmus ${leaf.sigAlgName}"))
            add(
                if (trustedIssuers.isEmpty()) {
                    SelfTestCheck("issuer", true, "Aussteller $issuer (keine Whitelist konfiguriert)", warning = true)
                } else {
                    val listed = trustedIssuers.any { issuer.contains(it, ignoreCase = true) }
                    SelfTestCheck("issuer", listed, "Aussteller $issuer" + if (listed) " steht auf der Whitelist" else " steht nicht auf der Whitelist")
                },
            )
            add(
                when {
                    daysLeft < 0 -> SelfTestCheck("validity", false, "abgelaufen am ${leaf.notAfter.toInstant()}")
                    daysLeft < warnBefore.toDays() -> SelfTestCheck("validity", true, "läuft in $daysLeft Tagen ab", warning = true)
                    else -> SelfTestCheck("validity", true, "noch $daysLeft Tage gültig")
                },
            )
        }
        return TlsSelfTestResult(
            url, protocol, leaf.subjectX500Principal.name, issuer, leaf.notAfter.toInstant(), keyType, keyBits, checks,
        )
    }

    private fun handshake(host: String, port: Int): Pair<String, List<X509Certificate>> {
        // Accept every chain here: the trust decision is one of the checks, not a reason to abort.
        val captureAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(captureAll), null) }
        (context.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(host, port), timeout.toMillis().toInt())
            socket.soTimeout = timeout.toMillis().toInt()
            if (!host.first().isDigit() && ':' !in host) {
                socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
            }
            socket.startHandshake()
            return socket.session.protocol to socket.session.peerCertificates.map { it as X509Certificate }
        }
    }

    private fun trusted(chain: List<X509Certificate>): Boolean = runCatching {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        manager.checkServerTrusted(chain.toTypedArray(), "UNKNOWN")
    }.isSuccess

    private fun matchesHost(certificate: X509Certificate, host: String): Boolean {
        val names = certificate.subjectAlternativeNames.orEmpty().filter { it[0] == 2 || it[0] == 7 }.map { it[1].toString().lowercase() }
        val h = host.lowercase()
        return names.any { name ->
            name == h || (name.startsWith("*.") && h.endsWith(name.substring(1)) && h.count { it == '.' } == name.count { it == '.' })
        }
    }
}
