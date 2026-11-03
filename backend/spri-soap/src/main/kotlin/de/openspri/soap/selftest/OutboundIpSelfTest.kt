package de.openspri.soap.selftest

import de.openspri.domain.partner.IpNetwork
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Result of the outbound IP self-test (AUFGABE 7.6). */
data class OutboundIpResult(val echoUrl: String, val detected: String?, val configured: List<String>, val checks: List<SelfTestCheck>) {
    val ok: Boolean get() = checks.all { it.ok }
}

/**
 * Finds out from which public address our outbound requests arrive and compares it with the configured outbound
 * addresses that partners allowlist. The request takes the same way as S/PRI requests: through the egress proxy
 * if one is configured. [echoUrl] must answer with the caller's IP address as plain text (e.g. a service of your
 * own, or a public one such as https://api.ipify.org); there is no default, so nothing is sent anywhere unasked.
 */
class OutboundIpSelfTest(private val echoUrl: URI?, private val outboundProxy: URI?, private val timeout: Duration = Duration.ofSeconds(10)) {

    val configured: Boolean get() = echoUrl != null

    fun run(configuredAddresses: List<String>): OutboundIpResult {
        val url = echoUrl ?: throw IllegalStateException("OPENSPRI_SELFTEST_IP_ECHO_URL ist nicht gesetzt")
        val client = HttpClient.newBuilder().connectTimeout(timeout).apply {
            outboundProxy?.let { proxy(ProxySelector.of(InetSocketAddress(it.host, if (it.port > 0) it.port else 3128))) }
        }.build()
        val detected = try {
            val body = client.send(HttpRequest.newBuilder(url).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofString()).body().trim()
            require(IP_LITERAL.matches(body)) { "Antwort ist keine IP-Adresse: ${body.take(60)}" }
            InetAddress.getByName(body)
        } catch (e: Exception) {
            return OutboundIpResult(url.toString(), null, configuredAddresses, listOf(SelfTestCheck("echo", false, "Abfrage von $url fehlgeschlagen: ${e.message}")))
        }
        return evaluate(url.toString(), detected, configuredAddresses)
    }

    fun evaluate(url: String, detected: InetAddress, configuredAddresses: List<String>): OutboundIpResult {
        val check = when {
            configuredAddresses.isEmpty() ->
                SelfTestCheck("match", false, "Keine Outbound-Adresse eingetragen (Einstellungen). Partner müssen ${detected.hostAddress} freischalten.")
            configuredAddresses.any { runCatching { detected in IpNetwork.parse(it) }.getOrDefault(false) } ->
                SelfTestCheck("match", true, "${detected.hostAddress} ist als Outbound-Adresse eingetragen")
            else -> SelfTestCheck(
                "match", false,
                "Anfragen kommen von ${detected.hostAddress}, eingetragen ist ${configuredAddresses.joinToString()}. Partner würden die Verbindung ablehnen.",
            )
        }
        return OutboundIpResult(url, detected.hostAddress, configuredAddresses, listOf(check))
    }

    private companion object {
        val IP_LITERAL = Regex("^[0-9a-fA-F:.]{2,45}$")
    }
}
