package de.openspri.app

import de.openspri.auth.ApiTokens
import de.openspri.auth.Role
import de.openspri.simulator.SimulatorMain
import de.openspri.simulator.TestCaseCodes
import de.openspri.simulator.KftTestCaseList
import de.openspri.soap.config.MessageCodeCatalogHolder
import openspri.testapps.ScenarioTestApplication
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The simulator as its own process (OPENSPRI_MODE=SIMULATOR, AUFGABE 10.3) against an instance via REST and SOAP. */
@SpringBootTest(
    classes = [ScenarioTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "openspri.inbox.initial-delay=0s", "openspri.inbox.poll-interval=50ms",
        "openspri.outbox.initial-delay=0s", "openspri.outbox.poll-interval=50ms",
    ],
)
class SimulatorModeTest(
    @Autowired private val tokens: ApiTokens,
    @Autowired private val codes: MessageCodeCatalogHolder,
    @Autowired private val settings: de.openspri.domain.InMemorySettings,
) {
    @LocalServerPort
    private var port = 0

    @Test
    fun `simulator registers itself, runs test cases by TF-ID and reports them`() {
        codes.set(TestCaseCodes.catalog(KftTestCaseList.all))
        // the simulator reads our certificate and S/PRI endpoint from the onboarding sheet (needs the company data)
        settings.save(
            de.openspri.domain.settings.CompanySettings(
                "Testnetz GmbH", carrierCode = "TSTNZ", contactEmail = "noc@testnetz.example", roleAuftraggeber = true, roleLeistungserbringer = true,
                publicSpriEndpoint = "http://127.0.0.1:$port/spri/v4",
            ),
        )
        val token = tokens.issue("sim", Role.ADMIN, Role.ADMIN.grantable, null, null, "Simulator", Instant.now()).plainText
        val server = SimulatorMain.start(
            SimulatorMain.Config(
                URI("http://127.0.0.1:$port"), token, publicUrl = "http://127.0.0.1:18090/spri/v4", port = 18090,
            ),
        )
        try {
            val http = HttpClient.newHttpClient()
            fun call(method: String, path: String, body: String? = null) = http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:18090$path"))
                    .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals("ok", call("GET", "/sim/health").body())
            val run = call("POST", "/sim/run", """{"tfIds":["S.1.1.01","S.8.1.13","Z.10.1.01"]}""")
            assertEquals(200, run.statusCode(), run.body())
            assertContains(run.body(), "\"passed\":3")
            assertEquals(400, call("POST", "/sim/run", """{"tfIds":["X.9.9"]}""").statusCode())

            val xml = call("GET", "/sim/report.xml").body()
            assertContains(xml, """tests="3" failures="0"""")
            assertContains(xml, "S_1_1_01_Standard_Ablauf")
            assertContains(call("GET", "/sim/report.html").body(), "3 von 3")
        } finally {
            server.stop(0)
        }
    }
}
