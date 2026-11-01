package de.openspri.app

import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.partner.IpNetwork
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerId
import de.openspri.soap.InMemoryInboundStore
import de.openspri.soap.InMemoryOutboundArchive
import de.openspri.soap.InMemoryPartnerDirectory
import de.openspri.soap.SigningCredentials
import de.openspri.soap.SpriClient
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real HTTP through Tomcat and CXF: the instance sends signed S/PRI requests to itself
 * (our client against our endpoint), so both directions of the interface are exercised.
 */
@SpringBootTest(classes = [SoapInMemoryTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SoapEndToEndTest(
    @Autowired private val client: SpriClient,
    @Autowired private val credentials: SigningCredentials,
    @Autowired private val partners: InMemoryPartnerDirectory,
    @Autowired private val inbound: InMemoryInboundStore,
    @Autowired private val outbound: InMemoryOutboundArchive,
) {
    @LocalServerPort
    private var port = 0

    private val endpoint get() = URI("http://localhost:$port/spri/v4")

    /** "Ourselves" as partner: our own certificate, reachable from localhost. */
    private fun self(allowed: String = "127.0.0.1") = Partner(
        PartnerId(1), "Selbsttest", "DEU.SELF", listOf(credentials.certificate),
        if (allowed.isEmpty()) emptyList() else listOf(IpNetwork.parse(allowed), IpNetwork.parse("::1")),
    )

    @BeforeEach
    fun reset() {
        partners.partners.clear()
        partners.partners += self()
        inbound.accepted.clear()
        inbound.rejected.clear()
        outbound.exchanges.clear()
    }

    @Test
    fun `order is signed, sent, verified and acknowledged with TEQ OK`() {
        val outcome = client.send(self(), endpoint, SoapOperation.ANNEHMEN_AUFTRAG, TestData.payload("neu-auftrag.xml", credentials), "TEST-0001")
        val acknowledged = assertIs<SpriClient.Outcome.Acknowledged>(outcome, outcome.toString())
        assertEquals(TeqCode.OK, acknowledged.teq)
        assertEquals("TEST-0001", inbound.accepted.single().second.externalOrderNumber)
        assertEquals(TeqCode.OK, outbound.exchanges.single().teqCode)
    }

    @Test
    fun `message is acknowledged on annehmenMeldung`() {
        val outcome = client.send(self(), endpoint, SoapOperation.ANNEHMEN_MELDUNG, TestData.payload("qeb-meldung.xml", credentials), "TEST-0001")
        assertEquals(TeqCode.OK, assertIs<SpriClient.Outcome.Acknowledged>(outcome, outcome.toString()).teq)
        assertEquals(SoapOperation.ANNEHMEN_MELDUNG, inbound.accepted.single().second.operation)
    }

    @Test
    fun `negative TEQ is reported to the sender`() {
        val payload = TestData.payload("neu-auftrag.xml", credentials) { it.getElementsByTagNameNS("*", "majorRelease").item(0).textContent = "3" }
        val outcome = client.send(self(), endpoint, SoapOperation.ANNEHMEN_AUFTRAG, payload, "TEST-0001")
        val acknowledged = assertIs<SpriClient.Outcome.Acknowledged>(outcome, outcome.toString())
        assertEquals(TeqCode.VERSION_INVALID, acknowledged.teq)
        assertEquals("Versionsnummer fehlerhaft", acknowledged.teqText)
    }

    @Test
    fun `addresses that are not allowlisted get HTTP 403`() {
        partners.partners.clear()
        partners.partners += self(allowed = "")
        val outcome = client.send(self(), endpoint, SoapOperation.ANNEHMEN_AUFTRAG, TestData.payload("neu-auftrag.xml", credentials), "TEST-0001")
        val failed = assertIs<SpriClient.Outcome.NotAcknowledged>(outcome)
        assertContains(failed.reason, "403")
        assertTrue(inbound.accepted.isEmpty() && inbound.rejected.isEmpty())
    }

    @Test
    fun `responses signed by an unexpected key are not accepted as TEQ`() {
        val stranger = Partner(PartnerId(2), "Fremd", "DEU.X", listOf(TestData.otherKey.certificate), emptyList())
        val outcome = client.send(stranger, endpoint, SoapOperation.ANNEHMEN_AUFTRAG, TestData.payload("neu-auftrag.xml", credentials), "TEST-0001")
        assertContains(assertIs<SpriClient.Outcome.NotAcknowledged>(outcome).reason, "response signature rejected")
    }

    @Test
    fun `WSDL is published with the S-PRI service definition`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("$endpoint?wsdl")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertContains(response.body(), "SpriService")
        assertContains(response.body(), "annehmenAuftrag")
        assertContains(response.body(), "localhost:$port/spri/v4")
    }

    @Test
    fun `signing key generated for development is AK compliant`() {
        assertTrue(SigningCredentials.rsaKeyBits(credentials.certificate) >= 3072)
    }
}
