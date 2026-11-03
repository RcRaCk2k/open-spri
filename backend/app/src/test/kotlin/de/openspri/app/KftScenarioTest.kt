package de.openspri.app

import de.openspri.auth.ApiTokens
import de.openspri.auth.Role
import de.openspri.domain.message.MessageCodeTableParser
import de.openspri.simulator.KftTestCaseList
import de.openspri.simulator.OpenSpriClient
import de.openspri.simulator.ScenarioPlanner
import de.openspri.simulator.ScenarioRunner
import de.openspri.simulator.SimulatedPartner
import de.openspri.simulator.TestCaseCodes
import de.openspri.soap.SigningCredentials
import de.openspri.soap.SigningKeyHolder
import de.openspri.soap.SpriSchema
import de.openspri.soap.config.MessageCodeCatalogHolder
import openspri.testapps.ScenarioTestApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * KFT scenarios (AUFGABE 10.1 item 5): every test case of the KFT test case list as its own test with the
 * TF-ID in its name. Our instance runs in its role, the [SimulatedPartner] plays the counterpart over signed
 * SOAP, both sides check every message including the message codes. Uses the AK message code table if it is
 * available locally, otherwise a table derived from the test case list.
 */
@SpringBootTest(
    classes = [ScenarioTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "openspri.inbox.initial-delay=0s", "openspri.inbox.poll-interval=50ms",
        "openspri.outbox.initial-delay=0s", "openspri.outbox.poll-interval=50ms", "openspri.outbox.overloaded-interval=1s",
        // the simulator drives all test cases through the REST API at full speed; the limit itself is tested in RateLimitTest
        "openspri.rate-limit.requests-per-minute=1000000",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KftScenarioTest(
    @Autowired private val tokens: ApiTokens,
    @Autowired private val keys: SigningKeyHolder,
    @Autowired private val codes: MessageCodeCatalogHolder,
) {
    @LocalServerPort
    private var port = 0

    private val cases = KftTestCaseList.all
    private lateinit var simulator: SimulatedPartner
    private lateinit var runner: ScenarioRunner

    @BeforeAll
    fun start() {
        val table = Path.of(System.getProperty("openspri.repoRoot", "../.."), "docs/spec-original/SPRI_Meldungscodetabelle.csv")
        codes.set(if (Files.exists(table)) MessageCodeTableParser.parse(Files.readAllBytes(table), table.fileName.toString()) else TestCaseCodes.catalog(cases))

        simulator = SimulatedPartner(
            "KFT-Simulator", SigningCredentials.generateSelfSigned("KFT-Simulator"), SpriSchema.load(),
            { keys.current().certificate to URI("http://127.0.0.1:$port/spri/v4") }, codes::get,
        )
        val endpoint = simulator.start()
        val us = OpenSpriClient(URI("http://127.0.0.1:$port"), tokens.issue("kft", Role.ADMIN, Role.ADMIN.grantable, null, null, "KFT-Szenarien", Instant.now()).plainText)
        val partner = us.objectNode().put("name", "KFT-Simulator").put("carrierCode", "KFTSIM").put("endpointUrl", endpoint.toString())
        partner.putArray("allowedSourceNetworks").add("127.0.0.1/32").add("::1/128")
        partner.putArray("certificatesPem").add(pem(simulator.credentials))
        val partnerId = us.post("/api/v1/partners", partner).path("id").longValue()
        runner = ScenarioRunner(us, partnerId, simulator, codes::get).also { it.prepare() }
    }

    @AfterAll
    fun stop() = simulator.close()

    @TestFactory
    fun `KFT test cases`(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest("${case.testName} [${if (case.partnerSheet) "AG" else "LE"}]") {
            val reason = runner.unsupported(case)
            assumeTrue(reason == null) { "noch nicht automatisiert: $reason" }
            val result = runner.run(ScenarioPlanner.plan(case))
            if (!result.passed) fail("${case.id}: ${result.failure}\n" + result.transcript.joinToString("\n") { "  $it" })
        }
    }

    /**
     * Coverage gate (AUFGABE 10.1 item 5): a test case without automation fails the build. During M5 the number
     * of open test cases may only go down (ratchet); at the end of M5 it is zero.
     */
    @Test
    fun `coverage gate - every test case is automated`() {
        val open = cases.filter { runner.unsupported(it) != null }
        assertTrue(open.size <= OPEN_TEST_CASES, "neu nicht automatisiert (${open.size} > $OPEN_TEST_CASES): ${open.joinToString { it.id }}")
    }

    private fun pem(credentials: SigningCredentials) =
        "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(credentials.certificate.encoded) + "\n-----END CERTIFICATE-----\n"

    private companion object {
        /** Lowered with every part of M5; must reach 0 (docs/AUFGABE-SPRI.md M5). */
        const val OPEN_TEST_CASES = 0
    }
}
