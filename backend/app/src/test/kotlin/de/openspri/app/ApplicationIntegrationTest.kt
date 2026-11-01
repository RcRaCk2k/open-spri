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
@SpringBootTest(classes = [OpenSpriApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ApplicationIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val partners: JdbcPartnerDirectory,
    @Autowired private val client: SpriClient,
    @Autowired private val credentials: SigningCredentials,
) {
    @LocalServerPort
    private var port = 0

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
    }
}
