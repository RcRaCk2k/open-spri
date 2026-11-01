package de.openspri.app

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import kotlin.test.assertEquals

/** Boots the full application against a real MariaDB. Skipped automatically without Docker. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ApplicationIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
) {

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
}
