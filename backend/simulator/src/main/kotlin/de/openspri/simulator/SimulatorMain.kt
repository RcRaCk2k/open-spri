package de.openspri.simulator

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.openspri.domain.message.MessageCode
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.soap.SigningCredentials
import de.openspri.soap.SpriSchema
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Executors

/**
 * The simulator as its own process (AUFGABE 10.3, container `spri-simulator`, `OPENSPRI_MODE=SIMULATOR`):
 * a KFT-like counterpart of an open-spri instance, controlled over HTTP.
 *
 * | Path | Purpose |
 * |---|---|
 * | `/spri/v4` | S/PRI endpoint of the simulator (signed SOAP) |
 * | `POST /sim/run` | runs test cases: body `{"tfIds": ["S.1.1.01", …]}`, empty = all; answers with the summary |
 * | `GET /sim/report.xml`, `GET /sim/report.html` | report of the last run (JUnit XML, HTML) |
 * | `GET /sim/health` | `ok` |
 *
 * Configuration (environment):
 * - `OPENSPRI_SIM_TARGET_URL`: REST base URL of the instance under test, e.g. `http://api:8080`
 * - `OPENSPRI_SIM_TOKEN`: API token of that instance (role ADMIN with `orders:override`, ADR-0008)
 * - `OPENSPRI_SIM_PUBLIC_URL`: S/PRI URL under which the instance reaches the simulator, e.g. `http://spri-simulator:8090/spri/v4`
 * - `OPENSPRI_SIM_TARGET_SPRI_URL`: optional, S/PRI endpoint of the instance (default: its onboarding sheet)
 * - `OPENSPRI_SIM_PORT`: default 8090
 * - `OPENSPRI_KFT_TEST_CASES`: path of the KFT test case list (xlsx, not part of the image)
 *
 * The simulator registers itself as partner `KFTSIM` of the instance with a key generated at start (only allowed
 * from the addresses in `OPENSPRI_SIM_SOURCE_NETWORKS`, default all private networks) and uses the instance's
 * active message code table.
 */
object SimulatorMain {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    /** Configuration, see the class documentation for the environment variables. */
    data class Config(
        val target: URI,
        val token: String,
        val publicUrl: String,
        val port: Int = 8090,
        val targetSpriUrl: URI? = null,
        val sourceNetworks: List<String> = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.1/32"),
    ) {
        companion object {
            fun fromEnvironment() = Config(
                URI(env("OPENSPRI_SIM_TARGET_URL")),
                env("OPENSPRI_SIM_TOKEN"),
                env("OPENSPRI_SIM_PUBLIC_URL"),
                System.getenv("OPENSPRI_SIM_PORT")?.toInt() ?: 8090,
                System.getenv("OPENSPRI_SIM_TARGET_SPRI_URL")?.takeIf { it.isNotBlank() }?.let(::URI),
                System.getenv("OPENSPRI_SIM_SOURCE_NETWORKS")?.split(',')?.map { it.trim() } ?: Config(URI("x:x"), "", "").sourceNetworks,
            )
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        start(Config.fromEnvironment())
    }

    /** Starts the simulator; returns the HTTP server (S/PRI endpoint and control API). */
    fun start(config: Config): HttpServer {
        val us = OpenSpriClient(config.target, config.token)
        val publicUrl = config.publicUrl
        val networks = config.sourceNetworks

        val sheet = us.get("/api/v1/partners/onboarding-sheet")
        val certificate = parsePem(sheet.path("signingCertificatePem").stringValue())
        val spriUrl = config.targetSpriUrl ?: sheet.path("spriEndpoint").stringValue(null)?.let(::URI)
            ?: error("Die Zielinstanz nennt keinen S/PRI-Endpunkt; OPENSPRI_SIM_TARGET_SPRI_URL setzen")
        var catalog = catalogOf(us)

        val credentials = SigningCredentials.generateSelfSigned("open-spri KFT-Simulator")
        val simulator = SimulatedPartner("KFT-Simulator", credentials, SpriSchema.load(), { certificate to spriUrl }, { catalog })
        val partnerId = register(us, credentials, publicUrl, networks)
        val runner = ScenarioRunner(us, partnerId, simulator, { catalog }).also { it.prepare() }
        val cases = KftTestCaseList.all
        var last: List<ScenarioResult> = emptyList()
        var lastAt = Instant.now()

        val http = HttpServer.create(InetSocketAddress(config.port), 0)
        simulator.register(http)
        http.createContext("/sim/health") { it.respond(200, "text/plain", "ok") }
        http.createContext("/sim/report.xml") { it.respond(200, "application/xml", ScenarioReport.junitXml(last, lastAt)) }
        http.createContext("/sim/report.html") { it.respond(200, "text/html; charset=utf-8", ScenarioReport.html(last, lastAt)) }
        http.createContext("/sim/run") { exchange ->
            if (exchange.requestMethod != "POST") return@createContext exchange.respond(405, "text/plain", "POST")
            val body = exchange.requestBody.readAllBytes()
            val ids = if (body.isEmpty()) emptyList() else json.readTree(body).path("tfIds").items().map { it.stringValue() }
            val selected = if (ids.isEmpty()) cases else cases.filter { it.id in ids }
            val unknown = ids - selected.map { it.id }.toSet()
            if (unknown.isNotEmpty()) return@createContext exchange.respond(400, "text/plain", "unbekannte TF-ID: $unknown")
            catalog = catalogOf(us) // the table may have changed since the start
            synchronized(runner) {
                last = selected.map { case ->
                    runner.unsupported(case)?.let { reason ->
                        ScenarioResult(ScenarioPlanner.plan(case), emptyList(), "nicht automatisiert: $reason", 0)
                    } ?: runner.run(ScenarioPlanner.plan(case))
                }
                lastAt = Instant.now()
            }
            val summary = json.createObjectNode().put("tests", last.size).put("passed", last.count { it.passed }).put("failed", last.count { !it.passed })
            summary.putArray("failures").also { failures -> last.filter { !it.passed }.forEach { failures.addObject().put("tfId", it.scenario.case.id).put("reason", it.failure) } }
            exchange.respond(if (last.all { it.passed }) 200 else 409, "application/json", json.writeValueAsString(summary))
        }
        http.executor = Executors.newVirtualThreadPerTaskExecutor()
        http.start()
        log.info("KFT-Simulator bereit auf Port {} (S/PRI {}), Ziel {} (S/PRI {}), {} Testfälle", http.address.port, publicUrl, config.target, spriUrl, cases.size)
        return http
    }

    /** Registers (or updates) the simulator as partner KFTSIM of the instance with the current certificate. */
    private fun register(us: OpenSpriClient, credentials: SigningCredentials, publicUrl: String, networks: List<String>): Long {
        val pem = pem(credentials.certificate)
        val existing = us.get("/api/v1/partners").items().firstOrNull { it.path("carrierCode").stringValue() == CARRIER_CODE }
        if (existing == null) {
            val body = us.objectNode().put("name", "KFT-Simulator").put("carrierCode", CARRIER_CODE).put("endpointUrl", publicUrl)
            networks.forEach { body.withArray("allowedSourceNetworks").add(it) }
            body.withArray("certificatesPem").add(pem)
            return us.post("/api/v1/partners", body).path("id").longValue()
        }
        val id = existing.path("id").longValue()
        us.call("PATCH", "/api/v1/partners/$id", us.objectNode().put("endpointUrl", publicUrl).put("enabled", true))
        us.post("/api/v1/partners/$id/certificates", us.objectNode().put("pem", pem))
        existing.path("certificates").items().forEach { old -> us.call("DELETE", "/api/v1/partners/$id/certificates/${old.path("id").longValue()}", null) }
        return id
    }

    /** The active message code table of the instance, so that both sides check the same codes. */
    private fun catalogOf(us: OpenSpriClient): MessageCodeCatalog {
        val table = us.get("/api/v1/message-codes")
        val codes = table.path("codes").items().filter { !it.path("localAddition").booleanValue() }.map {
            MessageCode(
                it.path("code").stringValue(), it.path("text").stringValue(),
                it.path("messageTypes").items().map { t -> t.stringValue() }.toSet(),
                it.path("businessCases").items().map { b -> b.stringValue() }.toSet(),
                emptySet(), setOf("S/PRI"),
            )
        }
        return MessageCodeCatalog(table.path("source").stringValue(), table.path("sha256").stringValue(), codes)
    }

    private fun HttpExchange.respond(status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }

    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name ist nicht gesetzt")

    private fun parsePem(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate

    private fun pem(certificate: X509Certificate) =
        "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded) + "\n-----END CERTIFICATE-----\n"

    private const val CARRIER_CODE = "KFTSIM"
}
