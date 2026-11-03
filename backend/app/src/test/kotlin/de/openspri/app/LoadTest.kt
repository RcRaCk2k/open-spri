package de.openspri.app

import de.openspri.domain.partner.IpNetwork
import de.openspri.persistence.JdbcPartnerDirectory
import de.openspri.soap.SigningCredentials
import de.openspri.soap.TestData
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Load and response time (AUFGABE 10.1 item 7, NF-01): signed S/PRI orders over real HTTP against the complete
 * application with MariaDB. Requests are signed beforehand, so only the server side is measured.
 *
 * - 50 parallel senders, 1000 orders: TEQ p95 < 1 s (internal target; the specification requires 10 s)
 * - one order with a 10 MB attachment: TEQ < 120 s (specification 11.4: up to 120 s with attachments)
 *
 * Not part of every build (`./gradlew :app:test -Pload`); CI runs it in its own job, the result is in the test log.
 */
@Tag("load")
@SpringBootTest(
    classes = [OpenSpriApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["openspri.inbox.initial-delay=1h", "openspri.outbox.initial-delay=1h", "openspri.webhooks.initial-delay=1h"],
)
@Testcontainers(disabledWithoutDocker = true)
class LoadTest(
    @Autowired private val partners: JdbcPartnerDirectory,
    @Autowired private val keys: de.openspri.soap.SigningKeyHolder,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @LocalServerPort
    private var port = 0

    private val credentials: SigningCredentials get() = keys.current()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mariadb = MariaDBContainer("mariadb:11.8")

        const val PARALLEL = 50
        const val REQUESTS = 1000
        const val WARMUP = 100
    }

    private fun partner(carrierCode: String) = partners.create(
        name = "Last $carrierCode", carrierCode = carrierCode, certificates = listOf(credentials.certificate),
        networks = listOf(IpNetwork.parse("127.0.0.1"), IpNetwork.parse("::1")),
    )

    private fun order(number: String, attachment: ByteArray? = null) = TestData.signedRequest("neu-auftrag.xml", credentials) { document ->
        document.getElementsByTagNameNS("*", "externeAuftragsnummer").item(0).textContent = number
        if (attachment != null) {
            val contact = document.getElementsByTagNameNS("*", "ansprechpartner").item(0)
            val anlage = document.createElementNS(null, "anlage")
            fun child(name: String, text: String) = anlage.appendChild(document.createElementNS(null, name).apply { textContent = text })
            child("dateiname", "lageplan.pdf")
            child("dateityp", "application/pdf")
            child("inhalt", Base64.getEncoder().encodeToString(attachment))
            child("anlagentyp", "Lageplan")
            contact.parentNode.insertBefore(anlage, contact)
        }
    }

    /** Sends one request; returns the duration in milliseconds and the TEQ code of the answer. */
    private fun send(request: ByteArray): Pair<Long, String> {
        val started = System.nanoTime()
        val response = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/spri/v4"))
                .timeout(Duration.ofSeconds(130))
                .header("Content-Type", "text/xml; charset=UTF-8")
                .header("SOAPAction", "\"http://spri.telekom.de/oss/v4/spri/annehmenAuftrag\"")
                .POST(HttpRequest.BodyPublishers.ofByteArray(request)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(200, response.statusCode(), response.body().take(500))
        val teq = Regex("<(?:\\w+:)?meldungscode>([^<]+)<").find(response.body())?.groupValues?.get(1) ?: response.body().take(300)
        return millis to teq
    }

    private fun percentile(values: List<Long>, p: Double) = values.sorted()[((values.size - 1) * p).toInt()]

    @Test
    fun `TEQ p95 below 1 s with 50 parallel senders (NF-01)`() {
        val id = partner("DEU.LOAD")
        val warmup = (1..WARMUP).map { order("WARM-%05d".format(it)) }
        val requests = (1..REQUESTS).map { order("LOAD-%05d".format(it)) }

        Executors.newFixedThreadPool(PARALLEL).use { pool -> warmup.map { pool.submit<Pair<Long, String>> { send(it) } }.forEach { it.get() } }
        val started = System.nanoTime()
        val results = Executors.newFixedThreadPool(PARALLEL).use { pool ->
            requests.map { pool.submit<Pair<Long, String>> { send(it) } }.map { it.get() }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        val durations = results.map { it.first }
        val p50 = percentile(durations, 0.50)
        val p95 = percentile(durations, 0.95)
        val p99 = percentile(durations, 0.99)
        println(
            "LOAD: %d orders, %d parallel, %.1f s, %.0f/s, p50 %d ms, p95 %d ms, p99 %d ms, max %d ms"
                .format(java.util.Locale.ROOT, REQUESTS, PARALLEL, seconds, REQUESTS / seconds, p50, p95, p99, durations.max()),
        )
        val accepted = jdbc.queryForObject(
            "SELECT COUNT(*) FROM soap_archive WHERE partner_id = ? AND direction = 'INBOUND' AND teq_code = 'OK' AND external_order_number LIKE 'LOAD-%'",
            Int::class.java, id.value,
        )
        assertEquals(REQUESTS, accepted, "every order archived with TEQ OK; answers: " + results.map { it.second }.distinct())
        assertTrue(p95 < 1000, "p95 = $p95 ms")
    }

    @Test
    fun `order with a 10 MB attachment is acknowledged within 120 s (specification 11-4)`() {
        val id = partner("DEU.ATTACH")
        val request = order("ATTACH-0001", Random(42).nextBytes(10 * 1024 * 1024))
        val (millis, _) = send(request)
        println("LOAD: order with 10 MB attachment (%d MB on the wire) acknowledged in %d ms".format(java.util.Locale.ROOT, request.size / 1024 / 1024, millis))
        assertEquals(
            "OK",
            jdbc.queryForObject("SELECT teq_code FROM soap_archive WHERE partner_id = ? AND external_order_number = 'ATTACH-0001'", String::class.java, id.value),
        )
        assertTrue(millis < 120_000, "$millis ms")
    }
}
