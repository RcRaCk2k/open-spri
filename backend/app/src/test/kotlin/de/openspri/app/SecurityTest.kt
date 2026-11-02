package de.openspri.app

import de.openspri.auth.ApiTokens
import de.openspri.auth.LocalUsers
import de.openspri.auth.Role
import de.openspri.auth.Scope
import openspri.testapps.SoapInMemoryTestApplication
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AUFGABE 7.3/7.5: authentication modules, scopes, session and CSRF - over real HTTP. */
@SpringBootTest(
    classes = [SoapInMemoryTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["openspri.inbox.initial-delay=1h", "openspri.outbox.initial-delay=1h"],
)
class SecurityTest(@Autowired private val tokens: ApiTokens, @Autowired private val users: LocalUsers) {

    @LocalServerPort
    private var port = 0

    private fun url(path: String) = URI("http://localhost:$port$path")

    private fun get(path: String, token: String? = null, client: HttpClient = HttpClient.newHttpClient()): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(url(path)).apply { token?.let { header("Authorization", "Bearer $it") } }.build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, json: String, token: String? = null, client: HttpClient = HttpClient.newHttpClient(), headers: Map<String, String> = emptyMap()) =
        client.send(
            HttpRequest.newBuilder(url(path)).POST(HttpRequest.BodyPublishers.ofString(json)).header("Content-Type", "application/json")
                .apply { token?.let { header("Authorization", "Bearer $it") }; headers.forEach { (k, v) -> header(k, v) } }.build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun issue(role: Role, scopes: Set<Scope>? = null) = tokens.issue("test", role, scopes, null, null, "test", Instant.now()).plainText

    @Test
    fun `public endpoints need no login`() {
        assertEquals(200, get("/api/v1/about").statusCode())
        assertEquals(200, get("/api/v1/auth/modules").statusCode())
        assertEquals(200, get("/api/v1/openapi.json").statusCode())
    }

    @Test
    fun `protected endpoints answer 401 without login`() {
        assertEquals(401, get("/api/v1/auth/me").statusCode())
        assertEquals(401, get("/api/v1/tokens").statusCode())
    }

    @Test
    fun `API token authenticates and scopes are enforced`() {
        val reader = issue(Role.INTEGRATION, setOf(Scope.ORDERS_READ))
        val me = get("/api/v1/auth/me", reader)
        assertEquals(200, me.statusCode())
        assertContains(me.body(), "orders:read")
        assertEquals(403, get("/api/v1/tokens", reader).statusCode(), "INTEGRATION may not manage tokens")
        assertEquals(200, get("/api/v1/tokens", issue(Role.ADMIN)).statusCode())
    }

    @Test
    fun `invalid and revoked tokens are rejected`() {
        assertEquals(401, get("/api/v1/auth/me", "spri_nonsense_value").statusCode())
        val issued = tokens.issue("revoked", Role.VIEWER, null, null, null, "test", Instant.now())
        val admin = issue(Role.ADMIN)
        assertEquals(204, HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(url("/api/v1/tokens/${issued.token.id}")).DELETE().header("Authorization", "Bearer $admin").build(),
            HttpResponse.BodyHandlers.ofString(),
        ).statusCode())
        assertEquals(401, get("/api/v1/auth/me", issued.plainText).statusCode())
    }

    @Test
    fun `scopes of a token cannot exceed its role`() {
        val admin = issue(Role.ADMIN)
        val response = post("/api/v1/tokens", """{"name":"x","role":"VIEWER","scopes":["orders:write"]}""", admin)
        assertEquals(400, response.statusCode())
    }

    @Test
    fun `orders override is in no role, only an explicit ADMIN token carries it (ADR-0008)`() {
        val admin = issue(Role.ADMIN)
        assertEquals(400, post("/api/v1/tokens", """{"name":"x","role":"INTEGRATION","scopes":["orders:override"]}""", admin).statusCode())
        val plain = post("/api/v1/tokens", """{"name":"x","role":"ADMIN"}""", admin)
        assertEquals(201, plain.statusCode(), plain.body())
        assertTrue("orders:override" !in plain.body())
        val explicit = post("/api/v1/tokens", """{"name":"kft","role":"ADMIN","scopes":["orders:read","orders:write","orders:override"]}""", admin)
        assertEquals(201, explicit.statusCode(), explicit.body())
        assertContains(explicit.body(), "orders:override")
    }

    @Test
    fun `session login and CSRF protection for the web frontend`() {
        val name = "admin${System.nanoTime() % 1_000_000}"
        users.create(name, "Admin", Role.ADMIN, "ein-sehr-langes-passwort", Instant.now())
        val cookies = CookieManager()
        val browser = HttpClient.newBuilder().cookieHandler(cookies).build()

        assertEquals(401, post("/api/v1/auth/login", """{"username":"$name","password":"falsch-falsch-falsch"}""", client = browser).statusCode())
        assertEquals(200, post("/api/v1/auth/login", """{"username":"$name","password":"ein-sehr-langes-passwort"}""", client = browser).statusCode())
        assertEquals(200, get("/api/v1/auth/me", client = browser).statusCode())

        // changing request with session but without CSRF token: rejected
        assertEquals(403, post("/api/v1/tokens", """{"name":"crm","role":"INTEGRATION"}""", client = browser).statusCode())
        // with the token from cookie XSRF-TOKEN in header X-XSRF-TOKEN: accepted
        get("/api/v1/auth/me", client = browser) // the SPA mode sets the cookie on a request
        val csrf = cookies.cookieStore.cookies.first { it.name == "XSRF-TOKEN" }.value
        val created = post("/api/v1/tokens", """{"name":"crm","role":"INTEGRATION"}""", client = browser, headers = mapOf("X-XSRF-TOKEN" to csrf))
        assertEquals(201, created.statusCode(), created.body())
        assertContains(created.body(), "\"token\":\"spri_")
    }
}
