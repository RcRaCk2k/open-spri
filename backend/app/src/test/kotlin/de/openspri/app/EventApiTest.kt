package de.openspri.app

import com.sun.net.httpserver.HttpServer
import de.openspri.auth.ApiTokens
import de.openspri.auth.Role
import de.openspri.domain.InMemoryOutbox
import de.openspri.domain.event.WebhookDispatcher
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerId
import de.openspri.rest.event.WebhookPoster
import de.openspri.soap.InMemoryPartnerDirectory
import de.openspri.soap.SecureXml
import de.openspri.soap.SpriJaxb
import de.openspri.soap.SpriJson
import de.openspri.soap.TestData
import de.telekom.spri.oss.v4.envelope.AnnehmenAuftragRequestType
import openspri.testapps.SoapInMemoryTestApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContains
import kotlin.test.assertEquals

@SpringBootTest(
    classes = [SoapInMemoryTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["openspri.inbox.initial-delay=1h", "openspri.outbox.initial-delay=1h", "openspri.webhooks.initial-delay=1h",
        "openspri.master-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
    ],
)
class EventApiTest(
    @Autowired private val tokens: ApiTokens,
    @Autowired private val partners: InMemoryPartnerDirectory,
    @Autowired private val outbox: InMemoryOutbox,
    @Autowired private val dispatcher: WebhookDispatcher,
) {
    @LocalServerPort
    private var port = 0

    private val admin by lazy { tokens.issue("admin", Role.ADMIN, null, null, null, "test", Instant.now()).plainText }

    private fun call(method: String, path: String, json: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .method(method, json?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json").header("Authorization", "Bearer $admin")
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun order(number: String) = """{"partnerId":78,"auftrag":${
        SpriJson.toJson(SpriJaxb.unmarshal(SecureXml.parse(TestData.sample("neu-auftrag.xml")).documentElement, AnnehmenAuftragRequestType::class.java))
            .get("auftrag").toString().replace("\"TEST-0001\"", "\"$number\"").replace("\"2026-12-01\"", "\"${LocalDate.now().plusDays(60)}\"")
    }}"""

    @BeforeEach
    fun partner() {
        if (partners.partners.none { it.id == PartnerId(78) }) {
            partners.partners += Partner(PartnerId(78), "Glasfaser Süd", "GFSUED", listOf(TestData.partnerKey.certificate), emptyList(), endpointUrl = "https://spri.sued.example/spri/v4")
        }
    }

    @Test
    fun `events appear in the feed and are delivered signed to webhooks`() {
        val received = CopyOnWriteArrayList<Triple<String, String, ByteArray>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hook") { exchange ->
                received += Triple(exchange.requestHeaders.getFirst("X-OpenSpri-Event"), exchange.requestHeaders.getFirst("X-OpenSpri-Signature"), exchange.requestBody.readAllBytes())
                exchange.sendResponseHeaders(204, -1); exchange.close()
            }
            start()
        }
        try {
            val created = call("POST", "/api/v1/webhooks", """{"url":"http://127.0.0.1:${server.address.port}/hook","types":["order.submitted"]}""")
            assertEquals(201, created.statusCode(), created.body())
            val secret = Regex("\"secret\":\"(whsec_[^\"]+)\"").find(created.body())!!.groupValues[1]
            assertEquals(false, call("GET", "/api/v1/webhooks").body().contains(secret), "the secret is shown only once")

            assertEquals(202, call("POST", "/api/v1/orders?send=true", order("EVT-0001")).statusCode())
            val feed = call("GET", "/api/v1/events?after=0&types=order.submitted")
            assertContains(feed.body(), "\"externalOrderNumber\":\"EVT-0001\"")

            dispatcher.drain()
            val (type, signature, body) = received.single()
            assertEquals("order.submitted", type)
            val (t, v1) = Regex("t=(\\d+),v1=([0-9a-f]{64})").find(signature)!!.destructured
            assertEquals(WebhookPoster.signature(secret.toByteArray(), t.toLong(), body), v1)
            assertContains(String(body), "\"externalOrderNumber\":\"EVT-0001\"")

            assertEquals(400, call("POST", "/api/v1/webhooks", """{"url":"ftp://x"}""").statusCode())
            assertEquals(400, call("POST", "/api/v1/webhooks", """{"url":"https://x.example","types":["order.unknown"]}""").statusCode())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a repeated request with the same Idempotency-Key is answered from the store`() {
        val key = mapOf("Idempotency-Key" to "order-IDEM-0001")
        val first = call("POST", "/api/v1/orders?send=true", order("IDEM-0001"), key)
        assertEquals(202, first.statusCode(), first.body())
        val count = outbox.entries.size

        val again = call("POST", "/api/v1/orders?send=true", order("IDEM-0001"), key)
        assertEquals(202, again.statusCode())
        assertEquals("true", again.headers().firstValue("Idempotent-Replayed").orElse(null))
        assertEquals(first.body(), again.body())
        assertEquals(count, outbox.entries.size, "nothing was sent twice")

        assertEquals(422, call("POST", "/api/v1/orders?send=true", order("IDEM-0002"), key).statusCode())

        // after an error the key can be used again
        val failing = mapOf("Idempotency-Key" to "order-IDEM-0003")
        assertEquals(404, call("POST", "/api/v1/orders?send=true", order("IDEM-0003").replace("\"partnerId\":78", "\"partnerId\":999"), failing).statusCode())
        assertEquals(202, call("POST", "/api/v1/orders?send=true", order("IDEM-0003"), failing).statusCode())
    }
}
