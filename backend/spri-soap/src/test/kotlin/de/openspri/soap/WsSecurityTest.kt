package de.openspri.soap

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.xml.crypto.dsig.XMLSignature
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WS-Security requirements: specification chapter 10, AK S&P security document V3.0 chapter 2.5. */
class WsSecurityTest {

    private val verifier = WsSecurityVerifier()
    private val wsuNs = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"

    @Test
    fun `signed envelope is accepted`() {
        val document = TestData.signedOnTheWire("neu-auftrag.xml", TestData.partnerKey)
        assertEquals(TestData.partnerKey.certificate, verifier.verify(document, listOf(TestData.partnerKey.certificate)))
    }

    @Test
    fun `signature uses SHA256withRSA, SHA-256 digest and a BinarySecurityToken`() {
        val xml = String(SecureXml.serialize(TestData.signedOnTheWire("neu-auftrag.xml", TestData.partnerKey)))
        assertContains(xml, WsSecurity.RSA_SHA256)
        assertContains(xml, WsSecurity.SHA256)
        assertContains(xml, "BinarySecurityToken")
        assertContains(xml, "http://www.w3.org/2001/10/xml-exc-c14n#")
    }

    @Test
    fun `interop - the JDK XML signature implementation validates our signature`() {
        val document = TestData.signedOnTheWire("neu-auftrag.xml", TestData.partnerKey)
        val body = SoapEnvelope.body(document)!!
        val signature = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0) as Element
        val context = DOMValidateContext(TestData.partnerKey.certificate.publicKey, signature).apply {
            setIdAttributeNS(body, wsuNs, "Id")
        }
        val xmlSignature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context)
        assertTrue(xmlSignature.validate(context), "JDK XMLSignature must validate the WS-Security signature")
    }

    @Test
    fun `interop - xmlsec1 validates our signature and detects tampering`(@TempDir dir: Path) {
        val xmlsec = listOf("/usr/bin/xmlsec1", "/usr/local/bin/xmlsec1").firstOrNull { Files.isExecutable(Path.of(it)) }
        assumeTrue(xmlsec != null, "xmlsec1 not installed")
        val certificate = dir.resolve("partner.pem")
        Files.writeString(
            certificate,
            "-----BEGIN CERTIFICATE-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(TestData.partnerKey.certificate.encoded) +
                "\n-----END CERTIFICATE-----\n",
        )
        // xmlsec1 does not understand wsse:SecurityTokenReference. KeyInfo is not part of the signed
        // content, so it is replaced by a KeyName that refers to the partner certificate passed directly.
        val document = TestData.signedOnTheWire("neu-auftrag.xml", TestData.partnerKey)
        val keyInfo = document.getElementsByTagNameNS(XMLSignature.XMLNS, "KeyInfo").item(0) as Element
        while (keyInfo.hasChildNodes()) keyInfo.removeChild(keyInfo.firstChild)
        keyInfo.appendChild(document.createElementNS(XMLSignature.XMLNS, "ds:KeyName")).textContent = "partner"

        fun xmlsecVerify(name: String, content: ByteArray): Pair<Int, String> {
            val file = dir.resolve(name).also { Files.write(it, content) }
            val process = ProcessBuilder(
                xmlsec, "--verify", "--pubkey-cert-pem:partner", certificate.toString(),
                "--id-attr:Id", "http://schemas.xmlsoap.org/soap/envelope/:Body", file.toString(),
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            return process.exitValue() to output
        }

        val (ok, output) = xmlsecVerify("message.xml", SecureXml.serialize(document))
        assertEquals(0, ok, "xmlsec1 output:\n$output")

        document.getElementsByTagNameNS("*", "externeAuftragsnummer").item(0).textContent = "MANIPULATED"
        val (tampered, _) = xmlsecVerify("tampered.xml", SecureXml.serialize(document))
        assertTrue(tampered != 0, "xmlsec1 must reject a modified body")
    }

    @Test
    fun `existing 2048-bit partner certificates are still accepted`() {
        val document = TestData.signedOnTheWire("neu-auftrag.xml", TestData.legacy2048Key)
        verifier.verify(document, listOf(TestData.legacy2048Key.certificate))
    }

    @Test
    fun `rejects a modified body`() {
        val document = TestData.signedOnTheWire("neu-auftrag.xml", TestData.partnerKey)
        document.getElementsByTagNameNS("*", "externeAuftragsnummer").item(0).textContent = "MANIPULATED"
        assertRejected("WS-Security processing failed") { verifier.verify(TestData.reparse(document), listOf(TestData.partnerKey.certificate)) }
    }

    @Test
    fun `rejects a certificate that is not registered for the partner`() {
        val document = TestData.signedOnTheWire("neu-auftrag.xml", TestData.otherKey)
        assertThrows<SignatureRejectedException> { verifier.verify(document, listOf(TestData.partnerKey.certificate)) }
    }

    @Test
    fun `rejects unsigned messages`() {
        assertRejected("no wsse:Security header") {
            verifier.verify(TestData.reparse(TestData.envelope("neu-auftrag.xml")), listOf(TestData.partnerKey.certificate))
        }
    }

    @Test
    fun `rejects SHA-1 signatures`() {
        val document = TestData.signedOnTheWire("neu-auftrag.xml", TestData.partnerKey, "http://www.w3.org/2000/09/xmldsig#rsa-sha1")
        assertThrows<SignatureRejectedException> { verifier.verify(document, listOf(TestData.partnerKey.certificate)) }
    }

    @Test
    fun `rejects keys below 2048 bit`() {
        val document = TestData.signedOnTheWire("neu-auftrag.xml", TestData.weakKey)
        assertThrows<SignatureRejectedException> { verifier.verify(document, listOf(TestData.weakKey.certificate)) }
    }

    @Test
    fun `rejects expired certificates`() {
        val document = TestData.signedOnTheWire("neu-auftrag.xml", TestData.expiredKey)
        assertThrows<SignatureRejectedException> { verifier.verify(document, listOf(TestData.expiredKey.certificate)) }
    }

    @Test
    fun `rejects signature wrapping - signed body moved to the header, forged body inserted`() {
        val document = TestData.signedOnTheWire("neu-auftrag.xml", TestData.partnerKey)
        val envelope = document.documentElement
        val signedBody = SoapEnvelope.body(document)!!
        val header = SecureXml.childElement(envelope, SecureXml.SOAP11_NS, "Header")!!
        val wrapper = document.createElementNS("urn:attacker", "a:Wrapper")
        header.appendChild(wrapper)
        envelope.removeChild(signedBody)
        wrapper.appendChild(signedBody)
        val forged = document.createElementNS(SecureXml.SOAP11_NS, "soapenv:Body")
        forged.appendChild(document.importNode(SecureXml.parse(TestData.sample("qeb-meldung.xml")).documentElement, true))
        envelope.appendChild(forged)
        assertThrows<SignatureRejectedException> { verifier.verify(TestData.reparse(document), listOf(TestData.partnerKey.certificate)) }
    }

    private fun assertRejected(reasonPrefix: String, block: () -> Unit) {
        val exception = assertThrows<SignatureRejectedException> { block() }
        assertTrue(exception.message!!.startsWith(reasonPrefix), "unexpected reason: ${exception.message}")
    }
}
