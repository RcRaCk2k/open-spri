package de.openspri.app

import de.openspri.auth.ApiTokens
import de.openspri.auth.Role
import de.openspri.domain.BusinessCase
import de.openspri.domain.InMemoryOrderRepository
import de.openspri.domain.InMemoryOutbox
import de.openspri.domain.delivery.DeliveryStatus
import de.openspri.domain.order.Order
import de.openspri.domain.order.OrderData
import de.openspri.domain.order.OrderLifecycle
import de.openspri.domain.order.OrderRole
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerId
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [SoapInMemoryTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["openspri.inbox.initial-delay=1h", "openspri.outbox.initial-delay=1h"],
)
class OrderApiTest(
    @Autowired private val tokens: ApiTokens,
    @Autowired private val partners: InMemoryPartnerDirectory,
    @Autowired private val orders: InMemoryOrderRepository,
    @Autowired private val outbox: InMemoryOutbox,
) {
    @LocalServerPort
    private var port = 0

    private val admin by lazy { tokens.issue("admin", Role.ADMIN, null, null, null, "test", Instant.now()).plainText }
    private val operatorAg by lazy { tokens.issue("ag", Role.OPERATOR_AG, null, null, null, "test", Instant.now()).plainText }

    private fun call(method: String, path: String, json: String? = null, token: String = admin): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .method(method, json?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json").header("Authorization", "Bearer $token").build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    /** The sample order as JSON with a fresh order number and a desired date in the future. */
    private fun auftrag(number: String): String {
        val request = SpriJaxb.unmarshal(SecureXml.parse(TestData.sample("neu-auftrag.xml")).documentElement, AnnehmenAuftragRequestType::class.java)
        return SpriJson.toJson(request).get("auftrag").toString()
            .replace("\"TEST-0001\"", "\"$number\"")
            .replace("\"2026-12-01\"", "\"${LocalDate.now().plusDays(60)}\"")
    }

    private fun id(body: String) = Regex("\"id\":(\\d+)").find(body)!!.groupValues[1].toLong()

    @BeforeEach
    fun partner() {
        if (partners.partners.none { it.id == PartnerId(77) }) {
            partners.partners += Partner(PartnerId(77), "Glasfaser Nord", "GFNORD", listOf(TestData.partnerKey.certificate), emptyList(), endpointUrl = "https://spri.nord.example/spri/v4")
        }
    }

    @Test
    fun `order, storno and the delivery monitor`() {
        val created = call("POST", "/api/v1/orders", """{"partnerId":77,"auftrag":${auftrag("API-0001")}}""", operatorAg)
        assertEquals(202, created.statusCode(), created.body())
        assertContains(created.body(), "\"phase\":\"SUBMITTED\"")
        assertContains(created.body(), "\"request\":\"STORNO\"")
        val orderId = id(created.body())

        val queued = call("GET", "/api/v1/deliveries?orderId=$orderId")
        assertEquals(200, queued.statusCode())
        assertContains(queued.body(), "\"status\":\"PENDING\"")
        assertContains(String(outbox.entries.last().payload), "<externeAuftragsnummer>API-0001</externeAuftragsnummer>")

        val storno = call("POST", "/api/v1/orders/$orderId/requests", """{"kind":"STORNO"}""", operatorAg)
        assertEquals(202, storno.statusCode(), storno.body())
        assertContains(storno.body(), "\"openRequests\":[\"STORNO\"]")
        assertContains(String(outbox.entries.last().payload), "<aenderungskennzeichen>Storno</aenderungskennzeichen>")

        val again = call("POST", "/api/v1/orders/$orderId/requests", """{"kind":"STORNO"}""", operatorAg)
        assertEquals(409, again.statusCode())
        assertContains(again.body(), "\"allowedActions\"")

        val deliveryId = outbox.entries.last().id!!
        assertEquals(409, call("POST", "/api/v1/deliveries/$deliveryId/retry").statusCode())
        val cancelled = call("POST", "/api/v1/deliveries/$deliveryId/cancel")
        assertEquals(200, cancelled.statusCode())
        assertContains(cancelled.body(), "\"status\":\"CANCELLED\"")

        val found = call("GET", "/api/v1/orders?q=API-00&role=AUFTRAGGEBER")
        assertContains(found.body(), "\"externalOrderNumber\":\"API-0001\"")
        assertEquals(2, Regex("\"kind\":\"(ORDER|REQUEST)\"").findAll(call("GET", "/api/v1/orders/$orderId/history").body()).count())
    }

    @Test
    fun `invalid order content is 400 with the JSON path, a duplicate number is 409`() {
        val invalid = call("POST", "/api/v1/orders", """{"partnerId":77,"auftrag":{"externeAuftragsnr":"X"}}""")
        assertEquals(400, invalid.statusCode())
        assertContains(invalid.body(), "externeAuftragsnummer")

        assertEquals(202, call("POST", "/api/v1/orders", """{"partnerId":77,"auftrag":${auftrag("API-0002")}}""").statusCode())
        val duplicate = call("POST", "/api/v1/orders", """{"partnerId":77,"auftrag":${auftrag("API-0002")}}""")
        assertEquals(409, duplicate.statusCode())
        assertContains(duplicate.body(), "bereits vergeben")

        assertEquals(404, call("POST", "/api/v1/orders", """{"partnerId":999,"auftrag":${auftrag("API-0003")}}""").statusCode())
    }

    @Test
    fun `as Leistungserbringer we answer with QEB, codes are checked`() {
        val now = Instant.now()
        val order = orders.save(
            Order(null, PartnerId(77), OrderRole.LEISTUNGSERBRINGER, OrderData("LE-0001", BusinessCase.NEU, clientNumber = "AG4711"),
                OrderLifecycle.submitted(BusinessCase.NEU), now, now),
        )
        val path = "/api/v1/orders/${order.id!!.value}/messages"

        val forbidden = call("POST", path, """{"type":"QEB","codes":[{"code":"0000"}]}""", operatorAg)
        assertEquals(403, forbidden.statusCode())

        val unknownCode = call("POST", path, """{"type":"QEB","codes":[{"code":"9876"}]}""")
        assertEquals(409, unknownCode.statusCode())
        assertContains(unknownCode.body(), "R-13")

        val qeb = call("POST", path, """{"type":"QEB","codes":[{"code":"0000"}]}""")
        assertEquals(202, qeb.statusCode(), qeb.body())
        assertContains(qeb.body(), "\"phase\":\"ACKNOWLEDGED\"")
        assertTrue(outbox.entries.any { it.orderId == order.id && it.status == DeliveryStatus.PENDING && String(it.payload).contains("MeldungstypQEBType") })

        val wrongTurn = call("POST", path, """{"type":"ERLM","codes":[{"code":"0000"}]}""")
        assertEquals(409, wrongTurn.statusCode())
        assertContains(wrongTurn.body(), "\"messageType\":\"ABM\"")
    }

    @Test
    fun `message code table is readable`() {
        val codes = call("GET", "/api/v1/message-codes?messageType=QEB")
        assertEquals(200, codes.statusCode())
        assertContains(codes.body(), "\"code\":\"0000\"")
    }
}
