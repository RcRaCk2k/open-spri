package de.openspri.soap

import org.w3c.dom.Document
import java.time.Duration

/** Shared fixtures. Keys are generated once per test run; all data is fictitious. */
object TestData {
    val partnerKey: SigningCredentials by lazy { SigningCredentials.generateSelfSigned("Test Partner") }
    val ownKey: SigningCredentials by lazy { SigningCredentials.generateSelfSigned("open-spri Test") }
    val otherKey: SigningCredentials by lazy { SigningCredentials.generateSelfSigned("Unknown Sender") }
    val legacy2048Key: SigningCredentials by lazy { SigningCredentials.generateSelfSigned("Legacy Partner", keyBits = 2048) }
    val weakKey: SigningCredentials by lazy { SigningCredentials.generateSelfSigned("Weak Partner", keyBits = 1024) }
    val expiredKey: SigningCredentials by lazy {
        SigningCredentials.generateSelfSigned("Expired Partner", validity = Duration.ofMinutes(-1))
    }

    fun sample(name: String): ByteArray =
        requireNotNull(TestData::class.java.getResourceAsStream("/samples/$name")) { "sample $name missing" }.readBytes()

    /**
     * A sample payload whose control header names [key] in signaturId, optionally modified by [edit],
     * as an XML element.
     */
    fun payload(sampleName: String, key: SigningCredentials, edit: (org.w3c.dom.Document) -> Unit = {}): org.w3c.dom.Element {
        val document = SecureXml.parse(sample(sampleName))
        document.getElementsByTagNameNS("*", "issuer").item(0).textContent = key.certificate.issuerX500Principal.name
        document.getElementsByTagNameNS("*", "serial").item(0).textContent = key.certificate.serialNumber.toString()
        edit(document)
        return document.documentElement
    }

    /** A signed request as bytes on the wire. */
    fun signedRequest(sampleName: String, key: SigningCredentials, edit: (org.w3c.dom.Document) -> Unit = {}): ByteArray =
        SecureXml.serialize(WsSecuritySigner(key).sign(SoapEnvelope.wrap(payload(sampleName, key, edit))))

    /** A sample payload wrapped into an unsigned SOAP envelope. */
    fun envelope(sampleName: String): Document = SoapEnvelope.wrap(SecureXml.parse(sample(sampleName)).documentElement)

    /** A sample payload wrapped and signed, then serialized and parsed again as on the wire. */
    fun signedOnTheWire(sampleName: String, key: SigningCredentials, algorithm: String = WsSecurity.RSA_SHA256): Document =
        SecureXml.parse(SecureXml.serialize(WsSecuritySigner(key, algorithm).sign(envelope(sampleName))))

    fun reparse(document: Document): Document = SecureXml.parse(SecureXml.serialize(document))
}
