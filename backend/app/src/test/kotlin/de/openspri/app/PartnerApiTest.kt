package de.openspri.app

import de.openspri.auth.ApiTokens
import de.openspri.auth.Role
import de.openspri.domain.InMemorySettings
import de.openspri.domain.settings.CompanySettings
import de.openspri.soap.SigningKeyService
import de.openspri.soap.TestData
import openspri.testapps.SoapInMemoryTestApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals

@SpringBootTest(
    classes = [SoapInMemoryTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["openspri.inbox.initial-delay=1h", "openspri.outbox.initial-delay=1h"],
)
class PartnerApiTest(@Autowired private val tokens: ApiTokens, @Autowired private val settings: InMemorySettings) {

    @LocalServerPort
    private var port = 0

    private val admin by lazy { tokens.issue("admin", Role.ADMIN, null, null, null, "test", Instant.now()).plainText }
    private val viewer by lazy { tokens.issue("viewer", Role.VIEWER, null, null, null, "test", Instant.now()).plainText }

    private fun call(method: String, path: String, json: String? = null, token: String = admin): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .method(method, json?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json").header("Authorization", "Bearer $token").build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun json(text: String) = text.replace("\n", "\\n")

    @BeforeEach
    fun company() {
        settings.settings = CompanySettings(
            name = "Beispiel GmbH", carrierCode = "BSPGMB", contactEmail = "betrieb@example.invalid",
            roleAuftraggeber = true, roleLeistungserbringer = true,
            publicSpriEndpoint = "https://spri.example.invalid/spri/v4", outboundAddresses = listOf("203.0.113.10"),
        )
    }

    @Test
    fun `create a partner with certificate and allowlist, then change the certificate`() {
        val pem = json(SigningKeyService.pem(TestData.partnerKey.certificate.encoded))
        val created = call("POST", "/api/v1/partners", """{"name":"Glasfaser Nord","carrierCode":"gfnord","endpointUrl":"https://spri.nord.example/spri/v4",
            "allowedSourceNetworks":["198.51.100.0/28"],"certificatesPem":["$pem"]}""")
        assertEquals(201, created.statusCode(), created.body())
        assertContains(created.body(), "\"carrierCode\":\"GFNORD\"")
        assertContains(created.body(), "\"keyBits\":3072")
        val id = Regex("\"id\":(\\d+)").find(created.body())!!.groupValues[1]

        val withSecond = call("POST", "/api/v1/partners/$id/certificates", """{"pem":"${json(SigningKeyService.pem(TestData.otherKey.certificate.encoded))}"}""")
        assertEquals(201, withSecond.statusCode())
        assertEquals(2, Regex("\"subject\"").findAll(withSecond.body()).count())

        val firstCertificate = Regex("\"certificates\":\\[\\{\"id\":(\\d+)").find(withSecond.body())!!.groupValues[1]
        assertEquals(204, call("DELETE", "/api/v1/partners/$id/certificates/$firstCertificate").statusCode())
        assertEquals(1, Regex("\"subject\"").findAll(call("GET", "/api/v1/partners/$id").body()).count())
    }

    @Test
    fun `invalid input is rejected with 400`() {
        assertEquals(400, call("POST", "/api/v1/partners", """{"name":"X","carrierCode":"X","certificatesPem":["kein zertifikat"]}""").statusCode())
        assertEquals(400, call("POST", "/api/v1/partners", """{"name":"X","carrierCode":"X","allowedSourceNetworks":["partner.example.de"]}""").statusCode())
        assertEquals(400, call("POST", "/api/v1/partners", """{"name":"X","carrierCode":"X","endpointUrl":"ftp://x"}""").statusCode())
    }

    @Test
    fun `viewers can read but not change partners`() {
        assertEquals(200, call("GET", "/api/v1/partners", token = viewer).statusCode())
        assertEquals(403, call("POST", "/api/v1/partners", """{"name":"X","carrierCode":"X"}""", token = viewer).statusCode())
    }

    @Test
    fun `WSDL carries our public endpoint, onboarding sheet our data`() {
        val wsdl = call("GET", "/api/v1/partners/wsdl")
        assertEquals(200, wsdl.statusCode())
        assertContains(wsdl.body(), """<soap:address location="https://spri.example.invalid/spri/v4"/>""")
        assertContains(wsdl.body(), "annehmenAuftrag")

        val sheet = call("GET", "/api/v1/partners/onboarding-sheet").body()
        assertContains(sheet, "\"carrierCode\":\"BSPGMB\"")
        assertContains(sheet, "203.0.113.10")
        assertContains(sheet, "BEGIN CERTIFICATE")
    }

    @Test
    fun `the egress proxy can be switched off per partner (AUFGABE 7-6)`() {
        val created = call("POST", "/api/v1/partners", """{"name":"Direkt","carrierCode":"DIREKT"}""")
        assertContains(created.body(), "\"useOutboundProxy\":true")
        val id = Regex("\"id\":(\\d+)").find(created.body())!!.groupValues[1]
        val changed = call("PATCH", "/api/v1/partners/$id", """{"useOutboundProxy":false}""")
        assertContains(changed.body(), "\"useOutboundProxy\":false")
        assertContains(call("PATCH", "/api/v1/partners/$id", """{"name":"Direkt GmbH"}""").body(), "\"useOutboundProxy\":false")
    }

    @Test
    fun `self-tests need settings admin, report an unreachable address and need an echo service for the outbound IP`() {
        assertEquals(403, call("GET", "/api/v1/selftest/tls", token = viewer).statusCode())
        val tls = call("GET", "/api/v1/selftest/tls")
        assertEquals(200, tls.statusCode(), tls.body())
        assertContains(tls.body(), "\"ok\":false")
        assertContains(tls.body(), "\"id\":\"connect\"")
        val outbound = call("GET", "/api/v1/selftest/outbound-ip")
        assertEquals(409, outbound.statusCode())
        assertContains(outbound.body(), "OPENSPRI_SELFTEST_IP_ECHO_URL")
    }
}
