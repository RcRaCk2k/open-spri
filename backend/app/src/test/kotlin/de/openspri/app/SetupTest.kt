package de.openspri.app

import de.openspri.rest.setup.SetupGuard
import de.openspri.soap.SigningKeyHolder
import openspri.testapps.SoapInMemoryTestApplication
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Setup wizard (AUFGABE 5.10): one-time token, one call, then locked. Own context (own in-memory state). */
@SpringBootTest(
    classes = [SoapInMemoryTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "openspri.inbox.initial-delay=1h", "openspri.outbox.initial-delay=1h",
        "openspri.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    ],
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SetupTest(@Autowired private val guard: SetupGuard, @Autowired private val keys: SigningKeyHolder) {

    @LocalServerPort
    private var port = 0

    private val client = HttpClient.newBuilder().cookieHandler(CookieManager()).build()

    private fun request(method: String, path: String, json: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .method(method, json?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json").apply { headers.forEach { (k, v) -> header(k, v) } }.build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private val setup = """{
        "admin": {"username": "admin", "displayName": "Administration", "password": "ein-langes-admin-passwort"},
        "company": {"name": "Beispiel GmbH", "carrierCode": "bspgmb", "contactEmail": "betrieb@example.invalid",
                    "roleAuftraggeber": true, "roleLeistungserbringer": true, "publicSpriEndpoint": "https://spri.example.invalid/spri/v4"},
        "signingKey": {"generate": true, "commonName": "S/PRI Signatur Beispiel GmbH"}
    }"""

    @Test
    @Order(1)
    fun `before setup - status open, temporary key, token required`() {
        assertContains(request("GET", "/api/v1/setup").body(), "\"completed\":false")
        assertEquals(SigningKeyHolder.Source.TEMPORARY, keys.source())
        assertEquals(403, request("POST", "/api/v1/setup", setup).statusCode())
        assertEquals(403, request("POST", "/api/v1/setup", setup, mapOf("X-Setup-Token" to "falsch")).statusCode())
    }

    @Test
    @Order(2)
    fun `setup with token creates admin, company and a stored signing key`() {
        val response = request("POST", "/api/v1/setup", setup, mapOf("X-Setup-Token" to guard.currentTokenForTests()!!))
        assertEquals(200, response.statusCode(), response.body())
        assertEquals(SigningKeyHolder.Source.DATABASE, keys.source())
        assertContains(keys.current().certificate.subjectX500Principal.name, "Beispiel GmbH")

        assertEquals(200, request("POST", "/api/v1/auth/login", """{"username":"admin","password":"ein-langes-admin-passwort"}""").statusCode())
        val settings = request("GET", "/api/v1/settings")
        assertContains(settings.body(), "\"carrierCode\":\"BSPGMB\"")
        val key = request("GET", "/api/v1/settings/signing-key").body()
        assertContains(key, "BEGIN CERTIFICATE")
        assertContains(key, "\"keyBits\":3072")
    }

    @Test
    @Order(3)
    fun `setup cannot be repeated`() {
        assertEquals(409, request("POST", "/api/v1/setup", setup, mapOf("X-Setup-Token" to "egal")).statusCode())
        assertContains(request("GET", "/api/v1/setup").body(), "\"completed\":true")
    }
}
