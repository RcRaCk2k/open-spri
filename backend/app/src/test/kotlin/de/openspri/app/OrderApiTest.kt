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
import de.openspri.domain.exchange.ArchivedExchange
import de.openspri.domain.exchange.Direction
import de.openspri.domain.exchange.InboundMessage
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.OrderEvent
import de.openspri.domain.order.OrderHistoryEntry
import de.openspri.domain.order.Transition
import de.openspri.soap.InMemoryInboundStore
import de.openspri.soap.InMemoryPartnerDirectory
import de.openspri.soap.SoapEnvelope
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
    @Autowired private val inbound: InMemoryInboundStore,
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
        val created = call("POST", "/api/v1/orders?send=true", """{"partnerId":77,"auftrag":${auftrag("API-0001")}}""", operatorAg)
        assertEquals(202, created.statusCode(), created.body())
        assertContains(created.body(), "\"phase\":\"SUBMITTED\"")
        assertContains(created.body(), "\"request\":\"STORNO\"")
        val orderId = id(created.body())

        val queued = call("GET", "/api/v1/deliveries?orderId=$orderId")
        assertEquals(200, queued.statusCode())
        assertContains(queued.body(), "\"status\":\"PENDING\"")
        assertContains(String(outbox.entries.last().payload), "<externeAuftragsnummer>API-0001</externeAuftragsnummer>")

        val storno = call("POST", "/api/v1/orders/$orderId/cancel", null, operatorAg)
        assertEquals(202, storno.statusCode(), storno.body())
        assertContains(storno.body(), "\"openRequests\":[\"STORNO\"]")
        assertContains(String(outbox.entries.last().payload), "<aenderungskennzeichen>Storno</aenderungskennzeichen>")

        val again = call("POST", "/api/v1/orders/$orderId/cancel", null, operatorAg)
        assertEquals(409, again.statusCode())
        assertContains(again.body(), "\"allowedActions\"")

        val deliveryId = outbox.entries.last().id!!
        assertEquals(409, call("POST", "/api/v1/deliveries/$deliveryId/retry").statusCode())
        val cancelled = call("POST", "/api/v1/deliveries/$deliveryId/cancel")
        assertEquals(200, cancelled.statusCode())
        assertContains(cancelled.body(), "\"status\":\"CANCELLED\"")

        val allowed = call("GET", "/api/v1/orders/$orderId/allowed-actions")
        assertEquals(200, allowed.statusCode())

        val found = call("GET", "/api/v1/orders?q=API-00&role=AUFTRAGGEBER")
        assertContains(found.body(), "\"externalOrderNumber\":\"API-0001\"")
        val history = call("GET", "/api/v1/orders/$orderId/history").body()
        assertEquals(2, Regex("\"kind\":\"(ORDER|REQUEST)\"").findAll(history).count())
        assertEquals(2, Regex("\"direction\":\"OUTBOUND\"").findAll(history).count())
    }

    @Test
    fun `terminverschiebung carries terminNeu`() {
        val created = call("POST", "/api/v1/orders?send=true", """{"partnerId":77,"auftrag":${auftrag("API-0004")}}""")
        val orderId = id(created.body())
        // the partner acknowledged the order (QEB): a Terminverschiebung is possible now (5.1.4)
        val order = orders.get(de.openspri.domain.order.OrderId(orderId))!!
        orders.save(order.copy(state = order.state.copy(phase = de.openspri.domain.order.Phase.ACKNOWLEDGED)))
        val date = LocalDate.now().plusDays(90)
        val tv = call("POST", "/api/v1/orders/$orderId/reschedule", """{"newDate":"$date"}""")
        assertEquals(202, tv.statusCode(), tv.body())
        assertContains(String(outbox.entries.last().payload), "<terminNeu>$date</terminNeu>")
        assertEquals(400, call("POST", "/api/v1/orders/$orderId/complaint", """{"auftrag":${auftrag("API-0005")}}""").statusCode())
    }

    @Test
    fun `invalid order content is 400 with the JSON path, a duplicate number is 409`() {
        val invalid = call("POST", "/api/v1/orders?send=true", """{"partnerId":77,"auftrag":{"externeAuftragsnr":"X"}}""")
        assertEquals(400, invalid.statusCode())
        assertContains(invalid.body(), "externeAuftragsnummer")

        assertEquals(202, call("POST", "/api/v1/orders?send=true", """{"partnerId":77,"auftrag":${auftrag("API-0002")}}""").statusCode())
        val duplicate = call("POST", "/api/v1/orders?send=true", """{"partnerId":77,"auftrag":${auftrag("API-0002")}}""")
        assertEquals(409, duplicate.statusCode())
        assertContains(duplicate.body(), "bereits vergeben")

        assertEquals(404, call("POST", "/api/v1/orders?send=true", """{"partnerId":999,"auftrag":${auftrag("API-0003")}}""").statusCode())
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
    fun `draft, change, send and delete (AUFGABE 5_6)`() {
        val created = call("POST", "/api/v1/orders", """{"partnerId":77,"auftrag":${auftrag("DRAFT-0001")}}""", operatorAg)
        assertEquals(201, created.statusCode(), created.body())
        assertContains(created.body(), "\"phase\":\"DRAFT\"")
        assertContains(created.body(), "\"kind\":\"SEND\"")
        val orderId = id(created.body())
        assertTrue(outbox.entries.none { it.orderId?.value == orderId })
        assertEquals(409, call("POST", "/api/v1/orders/$orderId/cancel", null, operatorAg).statusCode())

        val content = call("GET", "/api/v1/orders/$orderId/draft")
        assertEquals(200, content.statusCode())
        assertContains(content.body(), "DRAFT-0001")
        val changed = call("PUT", "/api/v1/orders/$orderId", """{"partnerId":77,"auftrag":${auftrag("DRAFT-0002")}}""", operatorAg)
        assertEquals(200, changed.statusCode(), changed.body())
        assertContains(changed.body(), "\"externalOrderNumber\":\"DRAFT-0002\"")
        assertEquals(400, call("PUT", "/api/v1/orders/$orderId", """{"partnerId":77,"auftrag":{"x":1}}""").statusCode())

        val sent = call("POST", "/api/v1/orders/$orderId/send", null, operatorAg)
        assertEquals(202, sent.statusCode(), sent.body())
        assertContains(sent.body(), "\"phase\":\"SUBMITTED\"")
        assertContains(String(outbox.entries.last { it.orderId?.value == orderId }.payload), "<externeAuftragsnummer>DRAFT-0002</externeAuftragsnummer>")
        assertEquals(409, call("POST", "/api/v1/orders/$orderId/send", null, operatorAg).statusCode())
        assertEquals(409, call("DELETE", "/api/v1/orders/$orderId", null, operatorAg).statusCode())
        assertEquals(404, call("GET", "/api/v1/orders/$orderId/draft").statusCode())

        val other = id(call("POST", "/api/v1/orders", """{"partnerId":77,"auftrag":${auftrag("DRAFT-0003")}}""").body())
        assertEquals(204, call("DELETE", "/api/v1/orders/$other", null, operatorAg).statusCode())
        assertEquals(404, call("GET", "/api/v1/orders/$other").statusCode())
    }

    @Test
    fun `as Leistungserbringer we terminate with KDLE (KUE-LE)`() {
        val body = """{"partnerId":77,"externalOrderNumber":"KDLE-0001","contractNumber":"V4711","clientNumber":"AG4711",
            "shutdownDate":"${LocalDate.now().plusDays(30)}","reason":"Testgrund","codes":[{"code":"8006"}]}"""
        assertEquals(403, call("POST", "/api/v1/terminations", body, operatorAg).statusCode())
        val terminated = call("POST", "/api/v1/terminations", body)
        assertEquals(202, terminated.statusCode(), terminated.body())
        assertContains(terminated.body(), "\"businessCase\":\"KUE-LE\"")
        assertContains(terminated.body(), "\"role\":\"LEISTUNGSERBRINGER\"")
        assertContains(terminated.body(), "\"messageType\":\"ERLM\"")
        val payload = String(outbox.entries.last().payload)
        assertContains(payload, "MeldungstypKDLEType")
        assertContains(payload, "<abschaltungsgrund>Testgrund</abschaltungsgrund>")
        assertEquals(409, call("POST", "/api/v1/terminations", body).statusCode())
        assertEquals(409, call("POST", "/api/v1/terminations", body.replace("KDLE-0001", "KDLE-0002").replace("8006", "0000")).statusCode())
    }

    @Test
    fun `as abgebender EKP we see the AKM-PV with its deadline and answer with RUEM-PV`() {
        val akm = SecureXml.parse(TestData.sample("akm-pv-meldung.xml")).documentElement
        val raw = SecureXml.serialize(SoapEnvelope.wrap(akm))
        val now = Instant.now()
        val archiveId = inbound.storeAccepted(
            ArchivedExchange(Direction.INBOUND, SoapOperation.ANNEHMEN_MELDUNG, PartnerId(77), "192.0.2.1", now, 5, raw, null, null, null, null, null, "PV-AUF-0001"),
            InboundMessage(PartnerId(77), SoapOperation.ANNEHMEN_MELDUNG, "PV-AUF-0001", SecureXml.serialize(akm)),
        )
        val announced = (OrderLifecycle.apply(OrderLifecycle.handoverAnnounced(), OrderEvent.Message(MessageType.AKM_PV)) as Transition.Accepted).state
        val order = orders.save(Order(null, PartnerId(77), OrderRole.ABGEBENDER_EKP, OrderData("PV-AUF-0001", BusinessCase.PV, contractNumber = "V0815"), announced, now, now))
        orders.append(OrderHistoryEntry(order.id, PartnerId(77), archiveId, OrderHistoryEntry.Kind.MESSAGE, MessageType.AKM_PV, at = now, direction = Direction.INBOUND))

        val open = call("GET", "/api/v1/pv-requests", null, operatorAg)
        assertEquals(200, open.statusCode(), open.body())
        assertContains(open.body(), "\"replyDeadline\":\"2026-11-08\"")
        assertContains(open.body(), "\"newProvider\":\"Beispiel Telekommunikation\"")
        assertContains(open.body(), "\"lineId\":\"DEU.GFNORD.0000000001\"")

        val path = "/api/v1/pv-requests/${order.id!!.value}/reply"
        val reply = call("POST", path, """{"consent":true,"providerName":"Unser Netz","codes":[{"code":"0021"}]}""", operatorAg)
        assertEquals(202, reply.statusCode(), reply.body())
        assertContains(reply.body(), "\"handoverPhase\":\"REPLIED\"")
        val payload = String(outbox.entries.last().payload)
        assertContains(payload, "MeldungstypRUEM-PVType")
        assertContains(payload, "<zustimmungProviderwechsel>J</zustimmungProviderwechsel>")
        assertContains(payload, "<lineId>DEU.GFNORD.0000000001</lineId>")
        assertContains(payload, "<auftraggebernummer>AG0042</auftraggebernummer>")

        assertEquals(409, call("POST", path, """{"consent":true,"codes":[{"code":"0021"}]}""", operatorAg).statusCode())
        assertTrue("PV-AUF-0001" !in call("GET", "/api/v1/pv-requests").body(), "answered requests are not open")
        assertContains(call("GET", "/api/v1/pv-requests?open=false").body(), "\"replyDue\":false")
        val ownOrder = id(call("POST", "/api/v1/orders", """{"partnerId":77,"auftrag":${auftrag("PV-0002")}}""").body())
        assertEquals(404, call("POST", "/api/v1/pv-requests/$ownOrder/reply", """{"consent":true,"codes":[{"code":"0021"}]}""").statusCode())
    }

    @Test
    fun `overriding own order rules needs the scope orders override (ADR-0008)`() {
        val far = auftrag("OVR-0001").replace(Regex("\"datum\":\"[0-9-]+\""), "\"datum\":\"${LocalDate.now().plusDays(200)}\"")
        val body = """{"partnerId":77,"auftrag":$far}"""
        val rejected = call("POST", "/api/v1/orders?send=true", body)
        assertEquals(409, rejected.statusCode())
        assertContains(rejected.body(), "R-05")
        assertEquals(403, call("POST", "/api/v1/orders?send=true&override=R-05", body).statusCode(), "ADMIN does not have the scope")
        val override = tokens.issue("kft", Role.ADMIN, Role.ADMIN.grantable, null, null, "test", Instant.now()).plainText
        assertEquals(400, call("POST", "/api/v1/orders?send=true&override=ALLES", body, override).statusCode())
        assertEquals(400, call("POST", "/api/v1/orders?override=R-05", body, override).statusCode(), "only when sending")
        val sent = call("POST", "/api/v1/orders?send=true&override=R-05", body, override)
        assertEquals(202, sent.statusCode(), sent.body())
        assertContains(call("GET", "/api/v1/orders/${id(sent.body())}/history").body(), "Übersteuert (ADR-0008): R-05")
    }

    @Test
    fun `compose builds a valid order for every business case of the Auftraggeber, missing fields are listed`() {
        val date = LocalDate.now().plusDays(40)
        val common = """"externalOrderNumber":"COMP-%s","clientNumber":"AG4711","serviceNumber":"LN0815","desiredDate":"$date",
            "contact":{"salutation":"9","lastName":"Auftragsmanagement","phone":"0123 456789"},"contractNumber":"V-4711","lineId":"DEU.GFNORD.0000004711""""
        val bodies = mapOf(
            "NEU" to """"products":["FTTH 100","Feste IP-Adresse"],"homeId":"HID-0815","timeWindow":"3","siteParty":{"salutation":"2","firstName":"Erika","lastName":"Musterfrau"},
                "site":{"street":"Musterstraße","houseNumber":"1","houseNumberSuffix":"a","postalCode":"12345","city":"Musterstadt","district":"Mitte","buildingPart":"Hinterhaus"}""",
            "KUE-AG" to """"products":[]""",
            "AEN-LMAE" to """"products":["FTTH 100","Feste IP-Adresse"]""",
            "LAE" to """"products":["FTTH 250"]""",
            "PV" to """"preAgreementId":"DEU.ABC.V000000001","products":["FTTH 100"],"siteParty":{"company":"Beispiel GmbH"},
                "site":{"street":"Industrieweg","houseNumber":"7","postalCode":"12345","city":"Musterstadt"}""",
            "EST" to """"fault":{"number":"ST-1","description":"Kein Sync am ONT","preChecked":true,"customer":{"salutation":"1","firstName":"Max","lastName":"Mustermann","phone":"0123 1"},"contactWanted":true}""",
            "DIAG-GET" to """"operations":["BBdown","BBup"]""",
            "DIAG-SET" to """"operations":["RESET"]""",
        )
        bodies.entries.forEachIndexed { i, (case, specific) ->
            val composed = call("POST", "/api/v1/orders/compose", """{"businessCase":"$case",${common.format(i)},$specific}""", operatorAg)
            assertEquals(200, composed.statusCode(), composed.body())
            assertContains(composed.body(), "\"missing\":[]", message = "$case: ${composed.body()}")
            val auftrag = Regex("\"auftrag\":(\\{.*}),\"missing\"").find(composed.body())!!.groupValues[1]
            val draft = call("POST", "/api/v1/orders", """{"partnerId":77,"auftrag":$auftrag}""", operatorAg)
            assertEquals(201, draft.statusCode(), "$case: ${draft.body()}")
            assertContains(draft.body(), "\"businessCase\":\"$case\"")
        }

        val partial = call("POST", "/api/v1/orders/compose", """{"businessCase":"NEU","products":["FTTH 100"],"contact":{"lastName":"AM"}}""", operatorAg).body()
        assertContains(partial, "auftragsmanagement/telefonnummer")
        assertContains(partial, "externeAuftragsnummer")
        assertContains(partial, "standortA/person oder firma")
        assertContains(partial, "\"@type\":\"FTTXBereitstellungType\"")
        assertEquals(400, call("POST", "/api/v1/orders/compose", """{"businessCase":"KUE-LE"}""", operatorAg).statusCode())
        assertEquals(400, call("POST", "/api/v1/orders/compose", """{"businessCase":"XYZ"}""", operatorAg).statusCode())
    }

    @Test
    fun `message code table is readable`() {
        val codes = call("GET", "/api/v1/message-codes?messageType=QEB")
        assertEquals(200, codes.statusCode())
        assertContains(codes.body(), "\"code\":\"0000\"")
    }
}
