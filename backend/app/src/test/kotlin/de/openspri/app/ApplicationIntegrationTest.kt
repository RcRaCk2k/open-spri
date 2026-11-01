package de.openspri.app

import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.partner.IpNetwork
import de.openspri.persistence.JdbcPartnerDirectory
import de.openspri.soap.SigningCredentials
import de.openspri.soap.SpriClient
import de.openspri.soap.TestData
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Boots the full application against a real MariaDB. Skipped automatically without Docker. */
@SpringBootTest(
    classes = [OpenSpriApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["openspri.inbox.initial-delay=0s", "openspri.inbox.poll-interval=200ms"],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ApplicationIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val partners: JdbcPartnerDirectory,
    @Autowired private val client: SpriClient,
    @Autowired private val keys: de.openspri.soap.SigningKeyHolder,
    @Autowired private val orderRepository: de.openspri.persistence.JdbcOrderRepository,
    @Autowired private val outbox: de.openspri.persistence.JdbcOutbox,
    @Autowired private val inbox: de.openspri.persistence.JdbcInboxQueue,
    @Autowired private val archive: de.openspri.persistence.JdbcMessageArchive,
) {
    @LocalServerPort
    private var port = 0

    private val credentials: SigningCredentials get() = keys.current()

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mariadb = MariaDBContainer("mariadb:11.8")
    }

    @Test
    fun `flyway baseline creates the default tenant`() {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tenant", Int::class.java))
        assertEquals(false, jdbc.queryForObject("SELECT completed FROM setup_state WHERE tenant_id = 1", Boolean::class.java))
    }

    @Test
    fun `health and about endpoints respond`() {
        mockMvc.get("/health").andExpect { status { isOk() } }
        mockMvc.get("/health/readiness").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/about").andExpect {
            status { isOk() }
            jsonPath("$.spriVersion") { value("4.4") }
            jsonPath("$.certifiedBuild") { value(false) }
        }
    }

    @Test
    fun `S-PRI roundtrip is archived in both directions and queued`() {
        val id = partners.create(
            name = "Selbsttest",
            carrierCode = "DEU.SELFIT",
            certificates = listOf(credentials.certificate),
            networks = listOf(IpNetwork.parse("127.0.0.1"), IpNetwork.parse("::1")),
        )
        val partner = partners.enabledPartners().single { it.id == id }
        val outcome = client.send(
            partner, URI("http://localhost:$port/spri/v4"), SoapOperation.ANNEHMEN_AUFTRAG,
            TestData.payload("neu-auftrag.xml", credentials), "TEST-0001",
        )
        assertEquals(TeqCode.OK, assertIs<SpriClient.Outcome.Acknowledged>(outcome, outcome.toString()).teq)

        val archived = jdbc.queryForList(
            "SELECT direction, teq_code FROM soap_archive WHERE partner_id = ? ORDER BY direction", id.value,
        )
        assertEquals(listOf(mapOf("direction" to "INBOUND", "teq_code" to "OK"), mapOf("direction" to "OUTBOUND", "teq_code" to "OK")), archived)
        assertEquals(
            "TEST-0001",
            jdbc.queryForObject("SELECT external_order_number FROM inbox_message WHERE partner_id = ?", String::class.java, id.value),
        )

        // M2: the scheduled inbox worker turns the accepted order into an order of the Leistungserbringer
        val deadline = System.currentTimeMillis() + 15_000
        var orders = 0
        while (orders == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spri_order WHERE partner_id = ? AND role = 'LEISTUNGSERBRINGER' AND phase = 'SUBMITTED'", Int::class.java, id.value,
            )!!
        }
        assertEquals(1, orders)
        assertEquals("PROCESSED", jdbc.queryForObject("SELECT status FROM inbox_message WHERE partner_id = ?", String::class.java, id.value))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM spri_order_history WHERE partner_id = ? AND kind = 'ORDER'", Int::class.java, id.value))
    }

    @Test
    fun `query ports for the REST API work on MariaDB`() {
        val partner = partners.create(name = "Abfrage", carrierCode = "DEU.QUERY", certificates = listOf(credentials.certificate), networks = emptyList())
        val now = java.time.Instant.now()
        val data = de.openspri.domain.order.OrderData("QRY_%-1", de.openspri.domain.BusinessCase.NEU, lineId = "DEU.QUERY.L1")
        val order = orderRepository.save(
            de.openspri.domain.order.Order(null, partner, de.openspri.domain.order.OrderRole.AUFTRAGGEBER, data,
                de.openspri.domain.order.OrderLifecycle.submitted(data.businessCase), now, now, sequenceKey = "k"),
        )
        orderRepository.append(
            de.openspri.domain.order.OrderHistoryEntry(order.id, partner, null, de.openspri.domain.order.OrderHistoryEntry.Kind.ORDER, findings = listOf("a", "b"), at = now),
        )
        outbox.enqueue(
            de.openspri.domain.delivery.OutboxEntry(null, partner, order.id, de.openspri.domain.delivery.RequestType.ORDER, SoapOperation.ANNEHMEN_AUFTRAG,
                data.externalOrderNumber, "k", "<a/>".toByteArray(), nextAttemptAt = now, createdAt = now),
        )

        // LIKE wildcards in the search text are matched literally
        assertEquals(listOf(order.id), orderRepository.search(de.openspri.domain.query.OrderFilter(text = "QRY_%")).map { it.id })
        assertEquals(emptyList(), orderRepository.search(de.openspri.domain.query.OrderFilter(text = "QRYX")))
        assertEquals(listOf(order.id), orderRepository.search(de.openspri.domain.query.OrderFilter(partnerId = partner, openOnly = true, text = "DEU.QUERY")).map { it.id })
        assertEquals(listOf("a", "b"), orderRepository.history(order.id!!).single().findings)
        assertEquals("<a/>", String(outbox.originalOrderPayload(order.id!!)!!))
        assertEquals(1, outbox.list(de.openspri.domain.delivery.DeliveryStatus.PENDING, order.id, null, 10).size)
        inbox.list(null, null, 10)

        val archiveId = archive.storeRejected(
            de.openspri.domain.exchange.ArchivedExchange(
                de.openspri.domain.exchange.Direction.INBOUND, null, partner, "127.0.0.1", now, 3, "<x/>".toByteArray(), null,
                TeqCode.STRUCTURE_INVALID, "test", null, null, null,
            ),
        )
        assertEquals(TeqCode.STRUCTURE_INVALID, archive.get(archiveId)!!.teqCode)
    }
}
