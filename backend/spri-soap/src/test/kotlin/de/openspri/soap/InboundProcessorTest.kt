package de.openspri.soap

import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.partner.IpNetwork
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerId
import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Entry check, AUFGABE chapter 5.3 / specification 10.4 and 11.2.3.1. */
class InboundProcessorTest {

    private val partnerAddress = InetAddress.getByName("192.0.2.10")
    private val partner = Partner(
        id = PartnerId(7),
        name = "Test Partner",
        carrierCode = "DEU.TEST",
        signatureCertificates = listOf(TestData.partnerKey.certificate),
        allowedSourceNetworks = listOf(IpNetwork.parse("192.0.2.0/28")),
    )
    private val store = InMemoryInboundStore()
    private val schema = SpriSchema.load()
    private val processor = InboundProcessor(
        partners = InMemoryPartnerDirectory(partner),
        store = store,
        schema = schema,
        verifier = WsSecurityVerifier(),
        responder = TeqResponder(TestData.ownKey),
    )

    private fun answer(request: ByteArray, soapAction: String? = null): InboundProcessor.Result.Answered =
        assertIs<InboundProcessor.Result.Answered>(processor.process(request, partnerAddress, soapAction))

    @Test
    fun `valid signed order is accepted, archived and queued`() {
        val result = answer(TestData.signedRequest("neu-auftrag.xml", TestData.partnerKey))
        assertEquals(TeqCode.OK, result.teq)
        val (exchange, message) = store.accepted.single()
        assertEquals(PartnerId(7), message.partnerId)
        assertEquals(SoapOperation.ANNEHMEN_AUFTRAG, message.operation)
        assertEquals("TEST-0001", message.externalOrderNumber)
        assertEquals(TestData.partnerKey.certificate.serialNumber.toString(), exchange.signerSerial)
        assertContains(String(message.payload), "annehmenAuftragRequest")
    }

    @Test
    fun `the TEQ response is schema-valid, signed with our key and names the order`() {
        val result = answer(TestData.signedRequest("neu-auftrag.xml", TestData.partnerKey))
        val document = SecureXml.parse(result.response)
        WsSecurityVerifier(minimumKeyBits = 3072).verify(document, listOf(TestData.ownKey.certificate))
        val payload = SecureXml.parse(SecureXml.serialize(SoapEnvelope.payload(document)!!)).documentElement
        assertEquals(emptyList(), schema.validate(payload))
        assertEquals("annehmenAuftragResponse", payload.localName)
        assertEquals("TEQ", SecureXml.findText(payload, "meldungsTyp"))
        assertEquals("OK", SecureXml.findText(payload, "meldungscode"))
        assertEquals("TEST-0001", SecureXml.findText(payload, "externeAuftragsnummer"))
        assertEquals("AG4711", SecureXml.findText(payload, "auftraggebernummer"))
    }

    @Test
    fun `valid signed message is accepted and answered with annehmenMeldungResponse`() {
        val result = answer(TestData.signedRequest("qeb-meldung.xml", TestData.partnerKey))
        assertEquals(TeqCode.OK, result.teq)
        assertEquals(SoapOperation.ANNEHMEN_MELDUNG, store.accepted.single().second.operation)
        assertEquals("annehmenMeldungResponse", SoapEnvelope.payload(SecureXml.parse(result.response))!!.localName)
    }

    @Test
    fun `unknown source address is forbidden and nothing is stored`() {
        val result = processor.process(TestData.signedRequest("neu-auftrag.xml", TestData.partnerKey), InetAddress.getByName("198.51.100.1"), null)
        assertEquals(InboundProcessor.Result.Forbidden, result)
        assertTrue(store.accepted.isEmpty() && store.rejected.isEmpty())
    }

    @Test
    fun `malformed XML is answered with 0995 and archived`() {
        val result = answer("<not-xml".toByteArray(), soapAction = "\"http://spri.telekom.de/oss/v4/spri/annehmenMeldung\"")
        assertEquals(TeqCode.STRUCTURE_INVALID, result.teq)
        // The operation is taken from the SOAPAction header when the payload cannot be read.
        assertEquals("annehmenMeldungResponse", SoapEnvelope.payload(SecureXml.parse(result.response))!!.localName)
        assertEquals(TeqCode.STRUCTURE_INVALID, store.rejected.single().teqCode)
    }

    @Test
    fun `unsigned request is answered with 0999`() {
        val unsigned = SecureXml.serialize(SoapEnvelope.wrap(TestData.payload("neu-auftrag.xml", TestData.partnerKey)))
        assertEquals(TeqCode.TECHNICAL_ERROR, answer(unsigned).teq)
        assertContains(store.rejected.single().rejectionReason!!, "no wsse:Security header")
    }

    @Test
    fun `request signed with an unregistered key is answered with 0999`() {
        assertEquals(TeqCode.TECHNICAL_ERROR, answer(TestData.signedRequest("neu-auftrag.xml", TestData.otherKey)).teq)
    }

    @Test
    fun `schema violation is answered with 0995`() {
        val request = TestData.signedRequest("neu-auftrag.xml", TestData.partnerKey) { document ->
            val element = document.getElementsByTagNameNS("*", "geschaeftsfallArt").item(0)
            element.parentNode.removeChild(element)
        }
        assertEquals(TeqCode.STRUCTURE_INVALID, answer(request).teq)
        assertContains(store.rejected.single().rejectionReason!!, "XSD")
    }

    @Test
    fun `unsupported major release is answered with 0993`() {
        val request = TestData.signedRequest("neu-auftrag.xml", TestData.partnerKey) { document ->
            document.getElementsByTagNameNS("*", "majorRelease").item(0).textContent = "3"
        }
        assertEquals(TeqCode.VERSION_INVALID, answer(request).teq)
    }

    @Test
    fun `malformed version is answered with 0993`() {
        val request = TestData.signedRequest("neu-auftrag.xml", TestData.partnerKey) { document ->
            document.getElementsByTagNameNS("*", "minorRelease").item(0).textContent = "x"
        }
        assertEquals(TeqCode.VERSION_INVALID, answer(request).teq)
    }

    @Test
    fun `newer minor version of the same major release is accepted`() {
        val request = TestData.signedRequest("neu-auftrag.xml", TestData.partnerKey) { document ->
            document.getElementsByTagNameNS("*", "minorRelease").item(0).textContent = "9"
        }
        assertEquals(TeqCode.OK, answer(request).teq)
    }

    @Test
    fun `signaturId that does not match the signing certificate is answered with 0999`() {
        val request = TestData.signedRequest("neu-auftrag.xml", TestData.partnerKey) { document ->
            document.getElementsByTagNameNS("*", "serial").item(0).textContent = "42"
        }
        assertEquals(TeqCode.TECHNICAL_ERROR, answer(request).teq)
        assertContains(store.rejected.single().rejectionReason!!, "signaturId")
    }

    @Test
    fun `storage failure is answered with 0999 so that the partner resends`() {
        store.failure = IllegalStateException("database down")
        assertEquals(TeqCode.TECHNICAL_ERROR, answer(TestData.signedRequest("neu-auftrag.xml", TestData.partnerKey)).teq)
    }

    private fun withAttributes(mode: AttributeMode) = InboundProcessor(
        partners = InMemoryPartnerDirectory(partner),
        store = store,
        schema = schema,
        verifier = WsSecurityVerifier(),
        responder = TeqResponder(TestData.ownKey),
        attributes = de.openspri.rules.AttributeValidator(),
        attributeMode = mode,
    )

    /** Terminverschiebung without terminNeu: XSD-valid (terminNeu is optional), Anlage 1 row 160: mandatory. */
    private val rescheduleWithoutDate = TestData.signedRequest("neu-auftrag.xml", TestData.partnerKey) { document ->
        document.getElementsByTagNameNS("*", "aenderungskennzeichen").item(0).textContent = "Terminverschiebung"
    }

    @Test
    fun `Anlage 1 violation is answered with 0995 in enforce mode`() {
        val result = assertIs<InboundProcessor.Result.Answered>(withAttributes(AttributeMode.ENFORCE).process(rescheduleWithoutDate, partnerAddress, null))
        assertEquals(TeqCode.STRUCTURE_INVALID, result.teq)
        assertContains(store.rejected.single().rejectionReason!!, "Anlage 1")
    }

    @Test
    fun `Anlage 1 violation is archived as finding in report mode`() {
        val result = assertIs<InboundProcessor.Result.Answered>(withAttributes(AttributeMode.REPORT).process(rescheduleWithoutDate, partnerAddress, null))
        assertEquals(TeqCode.OK, result.teq)
        assertContains(store.accepted.single().first.findings!!, "Termin Neu")
    }

    @Test
    fun `compliant order passes the Anlage 1 check`() {
        val result = assertIs<InboundProcessor.Result.Answered>(
            withAttributes(AttributeMode.ENFORCE).process(TestData.signedRequest("neu-auftrag.xml", TestData.partnerKey), partnerAddress, null),
        )
        assertEquals(TeqCode.OK, result.teq)
        assertEquals(null, store.accepted.single().first.findings)
    }
}
