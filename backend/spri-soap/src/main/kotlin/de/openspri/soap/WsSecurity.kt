package de.openspri.soap

import org.apache.wss4j.common.crypto.Crypto
import org.apache.wss4j.common.crypto.Merlin
import org.apache.wss4j.common.ext.WSSecurityException
import org.apache.wss4j.dom.WSConstants
import org.apache.wss4j.dom.WSDataRef
import org.apache.wss4j.dom.engine.WSSConfig
import org.apache.wss4j.dom.engine.WSSecurityEngine
import org.apache.wss4j.dom.engine.WSSecurityEngineResult
import org.apache.wss4j.dom.handler.RequestData
import org.apache.wss4j.dom.message.WSSecHeader
import org.apache.wss4j.dom.message.WSSecSignature
import org.apache.wss4j.common.WSEncryptionPart
import org.w3c.dom.Document
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * WS-Security (SOAP Message Security 1.1) as required by the S/PRI specification (chapter 10) and the
 * AK S&P security document V3.0, chapter 2.5, and as described by Anlage 3 policy.xml:
 * - X.509 v3 token, included as BinarySecurityToken (direct reference),
 * - the SOAP Body is signed (SignedParts: Body),
 * - signature algorithm SHA256withRSA, digest SHA-256, exclusive canonicalization.
 *
 * Note: policy.xml names the AlgorithmSuite "Basic256", whose default signature algorithm is RSA-SHA1.
 * The newer security document explicitly requires SHA256withRSA, which is what we use and accept
 * (docs/spec-abweichungen.md, SA-004).
 */
object WsSecurity {

    const val RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"
    const val RSA_SHA384 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha384"
    const val RSA_SHA512 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512"
    const val SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256"
    const val SHA384 = "http://www.w3.org/2001/04/xmldsig-more#sha384"
    const val SHA512 = "http://www.w3.org/2001/04/xmlenc#sha512"

    /** Signature algorithms accepted from partners: SHA-2 with RSA only. */
    val ACCEPTED_SIGNATURE_METHODS = setOf(RSA_SHA256, RSA_SHA384, RSA_SHA512)
    val ACCEPTED_DIGEST_METHODS = setOf(SHA256, SHA384, SHA512)

    init {
        WSSConfig.init()
    }
}

/** Signs outgoing SOAP envelopes with our [SigningCredentials]. */
class WsSecuritySigner(
    private val credentials: SigningCredentials,
    private val signatureAlgorithm: String = WsSecurity.RSA_SHA256,
) {
    private val crypto: Crypto = Merlin().apply {
        keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(SigningCredentials.ALIAS, credentials.privateKey, KEY_PASSWORD, arrayOf(credentials.certificate))
        }
    }

    init {
        WsSecurity.ACCEPTED_SIGNATURE_METHODS // triggers WSSConfig.init()
    }

    /** Adds a wsse:Security header with a signature over the Body to [document] (in place). */
    fun sign(document: Document): Document {
        val header = WSSecHeader(document).apply { insertSecurityHeader() }
        val algorithm = signatureAlgorithm // not shadowed by WSSecSignature.getSignatureAlgorithm() inside apply
        WSSecSignature(header).apply {
            setUserInfo(SigningCredentials.ALIAS, String(KEY_PASSWORD))
            keyIdentifierType = WSConstants.BST_DIRECT_REFERENCE
            setSignatureAlgorithm(algorithm)
            setDigestAlgo(WsSecurity.SHA256)
            setSigCanonicalization(WSConstants.C14N_EXCL_OMIT_COMMENTS)
            parts.add(WSEncryptionPart(WSConstants.ELEM_BODY, SecureXml.SOAP11_NS, ""))
            build(crypto)
        }
        return document
    }

    private companion object {
        val KEY_PASSWORD = "in-memory".toCharArray()
    }
}

/** Reason why a signature was rejected. Logged and archived internally, never sent to the partner. */
class SignatureRejectedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Verifies the WS-Security signature of an incoming SOAP envelope against the certificates registered
 * for the sending partner. Besides cryptographic validity it enforces:
 * - exactly one signature, covering exactly the Body element of this envelope (protection against
 *   signature wrapping),
 * - accepted signature and digest algorithms (SHA-2 only),
 * - minimum RSA key size ([minimumKeyBits]; the AK accepts existing 2048-bit certificates until expiry),
 * - the signing certificate is one of [trustedCertificates] and currently valid.
 */
class WsSecurityVerifier(private val minimumKeyBits: Int = 2048) {

    init {
        WsSecurity.ACCEPTED_SIGNATURE_METHODS // triggers WSSConfig.init()
    }

    fun verify(document: Document, trustedCertificates: Collection<X509Certificate>): X509Certificate {
        if (trustedCertificates.isEmpty()) throw SignatureRejectedException("no signature certificate configured for partner")
        val body = SoapEnvelope.body(document) ?: throw SignatureRejectedException("not a SOAP 1.1 envelope")

        val trustStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            trustedCertificates.forEachIndexed { index, certificate -> setCertificateEntry("partner-$index", certificate) }
        }
        val requestData = RequestData().apply {
            sigVerCrypto = Merlin().apply { setTrustStore(trustStore) }
            wssConfig = WSSConfig.getNewInstance()
        }
        val result = try {
            WSSecurityEngine().processSecurityHeader(document, requestData)
        } catch (e: WSSecurityException) {
            throw SignatureRejectedException("WS-Security processing failed: ${e.message}", e)
        } ?: throw SignatureRejectedException("no wsse:Security header")

        val signatures = result.actionResults[WSConstants.SIGN].orEmpty()
        if (signatures.size != 1) throw SignatureRejectedException("expected exactly one signature, found ${signatures.size}")
        val signature = signatures.single()

        val certificate = signature[WSSecurityEngineResult.TAG_X509_CERTIFICATE] as? X509Certificate
            ?: throw SignatureRejectedException("signature does not reference an X.509 certificate")
        if (trustedCertificates.none { it == certificate }) {
            throw SignatureRejectedException("certificate ${certificate.subjectX500Principal} is not registered for this partner")
        }
        try {
            certificate.checkValidity()
        } catch (e: java.security.cert.CertificateException) {
            throw SignatureRejectedException("certificate is not valid: ${e.message}", e)
        }
        val keyBits = SigningCredentials.rsaKeyBits(certificate)
        if (keyBits < minimumKeyBits) throw SignatureRejectedException("RSA key has $keyBits bit, at least $minimumKeyBits required")

        val method = signature[WSSecurityEngineResult.TAG_SIGNATURE_METHOD] as? String
        if (method !in WsSecurity.ACCEPTED_SIGNATURE_METHODS) throw SignatureRejectedException("signature algorithm $method not accepted")

        @Suppress("UNCHECKED_CAST")
        val references = signature[WSSecurityEngineResult.TAG_DATA_REF_URIS] as? List<WSDataRef> ?: emptyList()
        if (references.none { it.protectedElement === body }) throw SignatureRejectedException("the SOAP Body is not signed")
        references.firstOrNull { it.digestAlgorithm !in WsSecurity.ACCEPTED_DIGEST_METHODS }?.let {
            throw SignatureRejectedException("digest algorithm ${it.digestAlgorithm} not accepted")
        }
        return certificate
    }
}
