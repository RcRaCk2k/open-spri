package de.openspri.soap

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * Our own key for WS-Security signatures (AK S&P security document V3.0, chapter 2.5):
 * X.509 v3, SHA256withRSA, RSA >= 3072 bit; self-signed certificates are allowed.
 * This key is deliberately separate from the TLS key (recommendation of the AK).
 */
class SigningCredentials(val privateKey: PrivateKey, val certificate: X509Certificate) {

    /** Enforces the AK requirements for our own key. Called when the production key is loaded. */
    fun requireAkCompliant(): SigningCredentials = apply {
        require(privateKey.algorithm == "RSA") { "The AK S&P requires SHA256withRSA signatures - an RSA key is needed" }
        require(rsaKeyBits(certificate) >= MIN_OWN_KEY_BITS) {
            "Signing key must have at least $MIN_OWN_KEY_BITS bit (AK S&P security document V3.0, 2.5)"
        }
    }

    companion object {
        const val MIN_OWN_KEY_BITS = 3072
        const val ALIAS = "spri-signature"

        /** Loads the key from a PKCS#12 keystore (e.g. a Docker secret). */
        fun fromPkcs12(input: InputStream, password: CharArray, alias: String? = null): SigningCredentials {
            val keyStore = KeyStore.getInstance("PKCS12").apply { load(input, password) }
            val keyAlias = alias ?: keyStore.aliases().toList().first { keyStore.isKeyEntry(it) }
            return SigningCredentials(
                keyStore.getKey(keyAlias, password) as PrivateKey,
                keyStore.getCertificate(keyAlias) as X509Certificate,
            )
        }

        /** Generates a new RSA key with a self-signed certificate (setup wizard, tests). */
        fun generateSelfSigned(
            commonName: String,
            keyBits: Int = MIN_OWN_KEY_BITS,
            validity: Duration = Duration.ofDays(3650),
            signatureAlgorithm: String = "SHA256withRSA",
        ): SigningCredentials {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(keyBits, SecureRandom()) }.generateKeyPair()
            val subject = X500Name("CN=$commonName")
            val now = Instant.now()
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(63, SecureRandom()).add(BigInteger.ONE),
                Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(validity)),
                subject,
                keyPair.public,
            )
                .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation))
            val certificate = JcaX509CertificateConverter()
                .getCertificate(builder.build(JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.private)))
            return SigningCredentials(keyPair.private, certificate)
        }

        fun rsaKeyBits(certificate: X509Certificate): Int =
            (certificate.publicKey as? java.security.interfaces.RSAPublicKey)?.modulus?.bitLength() ?: 0
    }
}
