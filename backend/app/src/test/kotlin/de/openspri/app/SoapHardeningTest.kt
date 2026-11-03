package de.openspri.app

import de.openspri.domain.partner.IpNetwork
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerId
import de.openspri.soap.InMemoryInboundStore
import de.openspri.soap.InMemoryPartnerDirectory
import de.openspri.soap.TestData
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
import java.nio.file.Files
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Security tests of the S/PRI endpoint over real HTTP (AUFGABE 7.3, 10.1 item 8): XXE, entity expansion and
 * oversized requests must neither leak data nor reach business processing.
 */
@SpringBootTest(
    classes = [SoapInMemoryTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["openspri.inbox.initial-delay=1h", "openspri.outbox.initial-delay=1h", "openspri.soap.max-request-bytes=1048576"],
)
class SoapHardeningTest(
    @Autowired private val keys: de.openspri.soap.SigningKeyHolder,
    @Autowired private val partners: InMemoryPartnerDirectory,
    @Autowired private val inbound: InMemoryInboundStore,
) {
    @LocalServerPort
    private var port = 0

    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun reset() {
        partners.partners.clear()
        partners.partners += Partner(
            PartnerId(1), "Härtung", "DEU.HARD", listOf(keys.current().certificate),
            listOf(IpNetwork.parse("127.0.0.1"), IpNetwork.parse("::1")),
        )
        inbound.clear()
    }

    private fun post(body: ByteArray): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://localhost:$port/spri/v4")).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "text/xml; charset=UTF-8").header("SOAPAction", "\"http://spri.telekom.de/oss/v4/spri/annehmenAuftrag\"")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /** A signed order whose text is rewritten after signing, e.g. to add a DOCTYPE. */
    private fun signedOrder(edit: (String) -> String) =
        edit(String(TestData.signedRequest("neu-auftrag.xml", keys.current()))).toByteArray()

    @Test
    fun `external entities are not resolved (XXE)`() {
        val secret = Files.createTempFile("openspri-xxe", ".txt").also { Files.writeString(it, "GEHEIM-XXE-4711") }
        val request = signedOrder { xml ->
            xml.replaceFirst(Regex("<\\?xml[^>]*\\?>"), "")
                .let { """<?xml version="1.0"?><!DOCTYPE e [<!ENTITY xxe SYSTEM "${secret.toUri()}">]>""" + it }
                .replace("TEST-0001", "&xxe;")
        }
        val response = post(request)
        assertFalse(response.body().contains("GEHEIM-XXE-4711"), response.body())
        assertTeq0995Archived(response)
        assertTrue(inbound.rejected.none { String(it.response ?: ByteArray(0)).contains("GEHEIM-XXE-4711") })
    }

    @Test
    fun `entity expansion is refused quickly (billion laughs)`() {
        val entities = (1..9).joinToString("") { i -> """<!ENTITY l$i "${"&l${i - 1};".repeat(10)}">""" }
        val request = signedOrder { xml ->
            xml.replaceFirst(Regex("<\\?xml[^>]*\\?>"), "")
                .let { """<?xml version="1.0"?><!DOCTYPE e [<!ENTITY l0 "lol">$entities]>""" + it }
                .replace("TEST-0001", "&l9;")
        }
        val started = System.nanoTime()
        val response = post(request)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "answered within 5 s")
        assertFalse(response.body().contains("lollollol"))
        assertTeq0995Archived(response)
    }

    @Test
    fun `requests above the size limit are refused and not processed`() {
        val request = signedOrder { xml -> xml.replace("<auftrag>", "<!--" + "x".repeat(2 * 1024 * 1024) + "--><auftrag>") }
        val response = post(request)
        assertTeq0995Archived(response)
        assertEquals(64 * 1024, inbound.rejected.single().request.size, "only the beginning is archived")
        assertTrue(inbound.rejected.single().rejectionReason!!.contains("exceeds 1048576 bytes"))
    }

    @Test
    fun `broken XML and an unknown SOAPAction are answered with a TEQ, not a SOAP fault`() {
        assertTeq0995Archived(post("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>".toByteArray()))
        inbound.clear()
        val wrongAction = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/spri/v4"))
                .header("Content-Type", "text/xml; charset=UTF-8").header("SOAPAction", "\"unbekannt\"")
                .POST(HttpRequest.BodyPublishers.ofByteArray(TestData.signedRequest("neu-auftrag.xml", keys.current()))).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, wrongAction.statusCode(), wrongAction.body())
        assertEquals(1, inbound.accepted.size, "the operation follows from the payload")
    }

    /** Every request gets a TEQ (11.2.3.1) and is archived (NF-09); nothing reaches business processing. */
    private fun assertTeq0995Archived(response: HttpResponse<String>) {
        assertEquals(200, response.statusCode(), response.body().take(500))
        assertTrue(response.body().contains("<meldungscode>0995</meldungscode>"), response.body().take(800))
        assertEquals(0, inbound.accepted.size, "nothing reaches business processing")
        assertEquals(1, inbound.rejected.size, "archived")
    }

    @Test
    fun `a valid order below the limit is still accepted`() {
        val response = post(TestData.signedRequest("neu-auftrag.xml", keys.current()))
        assertEquals(200, response.statusCode())
        assertEquals(1, inbound.accepted.size, response.body())
    }
}
