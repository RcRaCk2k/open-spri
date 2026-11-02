package de.openspri.app

import de.openspri.auth.ApiTokens
import de.openspri.auth.Role
import de.openspri.domain.message.MessageCodeTableParser
import de.openspri.simulator.InstanceCounterpart
import de.openspri.simulator.KftTestCaseList
import de.openspri.simulator.OpenSpriClient
import de.openspri.simulator.ScenarioPlanner
import de.openspri.simulator.ScenarioRunner
import de.openspri.simulator.TestCaseCodes
import de.openspri.soap.SigningKeyHolder
import de.openspri.soap.config.MessageCodeCatalogHolder
import openspri.testapps.ScenarioTestApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import kotlin.test.fail

/**
 * Self-test AG ↔ LE (AUFGABE 10.1 item 6): two open-spri instances play every KFT test case against each other,
 * both driven only through their REST API. Runs nightly (`./gradlew :app:test -Pselftest`), not in every build.
 */
@Tag("selftest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KftSelfTest {
    private lateinit var a: ConfigurableApplicationContext
    private lateinit var b: ConfigurableApplicationContext
    private lateinit var runner: ScenarioRunner
    private val cases = KftTestCaseList.all

    /** Arguments, not default properties: they must win over application.yaml (server.port 8080). */
    private fun start(name: String): ConfigurableApplicationContext = SpringApplicationBuilder(ScenarioTestApplication::class.java).run(
        "--server.port=0", "--spring.application.name=$name",
        "--openspri.inbox.initial-delay=0s", "--openspri.inbox.poll-interval=50ms",
        "--openspri.outbox.initial-delay=0s", "--openspri.outbox.poll-interval=50ms",
    )

    private fun ConfigurableApplicationContext.url() = "http://127.0.0.1:${environment.getProperty("local.server.port")}"
    private fun ConfigurableApplicationContext.client() = OpenSpriClient(
        URI(url()),
        getBean(ApiTokens::class.java).issue("selftest", Role.ADMIN, Role.ADMIN.grantable, null, null, "Selbsttest", Instant.now()).plainText,
    )

    /** [other] as partner of [client]; returns its partner id. */
    private fun register(client: OpenSpriClient, name: String, other: ConfigurableApplicationContext): Long {
        val body = client.objectNode().put("name", name).put("carrierCode", name.uppercase().take(6)).put("endpointUrl", "${other.url()}/spri/v4")
        body.putArray("allowedSourceNetworks").add("127.0.0.1/32").add("::1/128")
        body.putArray("certificatesPem").add(pem(other.getBean(SigningKeyHolder::class.java).current().certificate))
        return client.post("/api/v1/partners", body).path("id").longValue()
    }

    @BeforeAll
    fun startBoth() {
        a = start("open-spri-a")
        b = start("open-spri-b")
        val table = Path.of(System.getProperty("openspri.repoRoot", "../.."), "docs/spec-original/SPRI_Meldungscodetabelle.csv")
        val catalog = if (Files.exists(table)) MessageCodeTableParser.parse(Files.readAllBytes(table), table.fileName.toString()) else TestCaseCodes.catalog(cases)
        listOf(a, b).forEach { it.getBean(MessageCodeCatalogHolder::class.java).set(catalog) }
        val clientA = a.client()
        val clientB = b.client()
        val bInA = register(clientA, "Selbsttest B", b)
        val aInB = register(clientB, "Selbsttest A", a)
        runner = ScenarioRunner(clientA, bInA, InstanceCounterpart("open-spri B", clientB, aInB), { catalog }).also { it.prepare() }
    }

    @AfterAll
    fun stopBoth() {
        if (::b.isInitialized) b.close()
        if (::a.isInitialized) a.close()
    }

    @TestFactory
    fun `KFT test cases between two open-spri instances`(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest("${case.testName} [A als ${if (case.partnerSheet) "AG" else "LE"}]") {
            val reason = runner.unsupported(case)
            assumeTrue(reason == null) { "im Selbsttest nicht möglich: $reason" }
            val result = runner.run(ScenarioPlanner.plan(case))
            if (!result.passed) fail("${case.id}: ${result.failure}\n" + result.transcript.joinToString("\n") { "  $it" })
        }
    }

    private fun pem(certificate: X509Certificate) =
        "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded) + "\n-----END CERTIFICATE-----\n"
}
