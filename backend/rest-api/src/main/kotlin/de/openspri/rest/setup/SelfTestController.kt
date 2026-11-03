package de.openspri.rest.setup

import de.openspri.domain.settings.SettingsRepository
import de.openspri.rest.auth.Requires
import de.openspri.soap.config.SpriSoapProperties
import de.openspri.soap.selftest.OutboundIpSelfTest
import de.openspri.soap.selftest.SelfTestCheck
import de.openspri.soap.selftest.TlsSelfTest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant

@Configuration(proxyBeanMethods = false)
class SelfTestConfiguration {
    /** OPENSPRI_SELFTEST_TRUSTED_ISSUERS: comma separated parts of the issuer DN (AK whitelist), empty = not checked. */
    @Bean
    fun tlsSelfTest(@Value("\${openspri.selftest.trusted-issuers:}") issuers: List<String>) = TlsSelfTest(issuers.map { it.trim() }.filter { it.isNotEmpty() })

    /** OPENSPRI_SELFTEST_IP_ECHO_URL: service that answers with the caller's IP; empty = test disabled. */
    @Bean
    fun outboundIpSelfTest(@Value("\${openspri.selftest.ip-echo-url:}") echoUrl: String, soap: SpriSoapProperties) =
        OutboundIpSelfTest(echoUrl.takeIf { it.isNotBlank() }?.let(URI::create), soap.client.outboundProxyUri())
}

@Schema(description = "Eine Prüfung eines Selbsttests")
data class SelfTestCheckDto(
    @field:Schema(description = "z. B. protocol, trust, key, validity, match", requiredMode = Schema.RequiredMode.REQUIRED) val id: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ok: Boolean,
    @field:Schema(description = "funktioniert, aber bald handeln (z. B. Zertifikat läuft bald ab)", requiredMode = Schema.RequiredMode.REQUIRED) val warning: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val detail: String,
) {
    companion object {
        fun of(c: SelfTestCheck) = SelfTestCheckDto(c.id, c.ok, c.warning, c.detail)
    }
}

@Schema(description = "Selbsttest des TLS-Zertifikats unserer öffentlichen Adresse (AUFGABE 7.4)")
data class TlsSelfTestDto(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val url: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ok: Boolean,
    val protocol: String?,
    val subject: String?,
    val issuer: String?,
    val notAfter: Instant?,
    @field:Schema(description = "RSA oder EC") val keyType: String?,
    val keyBits: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val checks: List<SelfTestCheckDto>,
)

@Schema(description = "Selbsttest der Outbound-IP (AUFGABE 7.6)")
data class OutboundIpSelfTestDto(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ok: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val echoUrl: String,
    @field:Schema(description = "Adresse, von der unsere Anfragen ankommen") val detected: String?,
    @field:Schema(description = "eingetragene Outbound-Adressen", requiredMode = Schema.RequiredMode.REQUIRED) val configured: List<String>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val checks: List<SelfTestCheckDto>,
)

@RestController
@RequestMapping("/api/v1/selftest", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Einstellungen")
class SelfTestController(
    private val settings: SettingsRepository,
    private val soap: SpriSoapProperties,
    private val tls: TlsSelfTest,
    private val outbound: OutboundIpSelfTest,
) {

    @GetMapping("/tls")
    @PreAuthorize(Requires.SETTINGS_ADMIN)
    @Operation(
        summary = "TLS-Zertifikat der öffentlichen Adresse prüfen",
        description = "Ruft die eigene öffentliche Adresse (S/PRI-Endpunkt aus den Einstellungen) so auf wie ein Partner und prüft Protokoll, " +
            "Vertrauenskette, Hostname, Schlüssel, Aussteller und Restlaufzeit (Warnung unter 14 Tagen).",
    )
    fun tls(): TlsSelfTestDto {
        val endpoint = settings.load()?.publicSpriEndpoint?.takeIf { it.isNotBlank() }
            ?: soap.publishedEndpointUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Kein öffentlicher S/PRI-Endpunkt eingetragen (Einstellungen)")
        val url = URI.create(endpoint)
        val r = tls.run(URI(url.scheme, null, url.host, url.port, "/", null, null))
        return TlsSelfTestDto(r.url, r.ok, r.protocol, r.subject, r.issuer, r.notAfter, r.keyType, r.keyBits, r.checks.map(SelfTestCheckDto::of))
    }

    @GetMapping("/outbound-ip")
    @PreAuthorize(Requires.SETTINGS_ADMIN)
    @Operation(
        summary = "Outbound-IP prüfen",
        description = "Fragt OPENSPRI_SELFTEST_IP_ECHO_URL (über den Egress-Proxy, falls gesetzt), von welcher Adresse unsere Anfragen kommen, " +
            "und vergleicht sie mit den eingetragenen Outbound-Adressen. 409, wenn kein Echo-Dienst konfiguriert ist.",
    )
    fun outboundIp(): OutboundIpSelfTestDto {
        if (!outbound.configured) throw IllegalStateException("Selbsttest nicht eingerichtet: OPENSPRI_SELFTEST_IP_ECHO_URL ist nicht gesetzt")
        val r = outbound.run(settings.load()?.outboundAddresses.orEmpty())
        return OutboundIpSelfTestDto(r.ok, r.echoUrl, r.detected, r.configured, r.checks.map(SelfTestCheckDto::of))
    }
}
