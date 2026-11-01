package de.openspri.soap

import de.openspri.domain.settings.SigningKeyRepository
import de.openspri.domain.settings.StoredSigningKey
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The signing key in use. Replaced at runtime (setup, key change) without restart; TEQ responses and the
 * client always sign with the current key.
 */
class SigningKeyHolder(initial: SigningCredentials, val source: Source) {
    enum class Source { KEYSTORE_FILE, DATABASE, TEMPORARY }

    private val current = AtomicReference(initial to source)

    fun current(): SigningCredentials = current.get().first
    fun source(): Source = current.get().second
    fun replace(credentials: SigningCredentials, source: Source) = current.set(credentials to source)
}

/**
 * Encryption of the stored signing key: AES-256-GCM with the master key from `OPENSPRI_MASTER_KEY`
 * (base64, 32 bytes, e.g. `openssl rand -base64 32`). The master key is never stored in the database.
 * Format: 12-byte IV followed by the ciphertext with tag.
 */
class KeyEncryption(masterKeyBase64: String) {
    private val key = SecretKeySpec(
        Base64.getDecoder().decode(masterKeyBase64.trim()).also { require(it.size == 32) { "OPENSPRI_MASTER_KEY muss 32 Bytes (base64) lang sein" } },
        "AES",
    )
    private val random = SecureRandom()

    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12)) }
        return cipher.doFinal(data, 12, data.size - 12)
    }
}

/** Generating, importing and storing our signing key (setup wizard, settings). */
class SigningKeyService(
    private val holder: SigningKeyHolder,
    private val repository: SigningKeyRepository,
    private val encryption: KeyEncryption?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Generates a new AK-compliant key (RSA 3072, SHA256withRSA, self-signed), stores and activates it. */
    fun generate(commonName: String, createdBy: String): SigningCredentials =
        activate(SigningCredentials.generateSelfSigned(commonName), createdBy)

    /** Imports a PKCS#12 keystore (e.g. from an existing installation), stores and activates it. */
    fun import(pkcs12: ByteArray, password: CharArray, createdBy: String): SigningCredentials =
        activate(SigningCredentials.fromPkcs12(ByteArrayInputStream(pkcs12), password).requireAkCompliant(), createdBy)

    private fun activate(credentials: SigningCredentials, createdBy: String): SigningCredentials {
        if (holder.source() == SigningKeyHolder.Source.KEYSTORE_FILE) {
            throw IllegalStateException("Der Signaturschlüssel wird über OPENSPRI_SOAP_SIGNING_KEYSTORE vorgegeben und kann hier nicht ersetzt werden")
        }
        val crypto = encryption ?: throw IllegalStateException(
            "OPENSPRI_MASTER_KEY ist nicht gesetzt - ohne Master-Schlüssel kann der Signaturschlüssel nicht gespeichert werden (docs/betrieb.md)",
        )
        repository.activate(
            StoredSigningKey(
                null, crypto.encrypt(toPkcs12(credentials)), credentials.certificate.subjectX500Principal.name,
                credentials.certificate.serialNumber.toString(), credentials.certificate.notAfter.toInstant(), createdBy, Instant.now(),
            ),
        )
        holder.replace(credentials, SigningKeyHolder.Source.DATABASE)
        log.info("New WS-Security signing key active: {} (serial {})", credentials.certificate.subjectX500Principal, credentials.certificate.serialNumber)
        return credentials
    }

    /** The certificate in PEM, to be sent to every partner. */
    fun certificatePem(): String = pem(holder.current().certificate.encoded)

    companion object {
        private val INTERNAL_PASSWORD = "open-spri".toCharArray()

        /** Startup: the keystore file wins, then the stored key, else a temporary key. */
        fun load(file: SigningCredentials?, repository: SigningKeyRepository, encryption: KeyEncryption?): SigningKeyHolder {
            if (file != null) return SigningKeyHolder(file, SigningKeyHolder.Source.KEYSTORE_FILE)
            val stored = repository.active()
            if (stored != null && encryption != null) {
                val credentials = SigningCredentials.fromPkcs12(ByteArrayInputStream(encryption.decrypt(stored.encryptedPkcs12)), INTERNAL_PASSWORD)
                return SigningKeyHolder(credentials, SigningKeyHolder.Source.DATABASE)
            }
            val log = LoggerFactory.getLogger(SigningKeyService::class.java)
            if (stored != null) log.error("A signing key is stored, but OPENSPRI_MASTER_KEY is missing - using a temporary key")
            else log.warn("No WS-Security signing key configured yet (setup wizard). A temporary key is used - not for production.")
            return SigningKeyHolder(SigningCredentials.generateSelfSigned("open-spri temporary key"), SigningKeyHolder.Source.TEMPORARY)
        }

        fun toPkcs12(credentials: SigningCredentials): ByteArray {
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(SigningCredentials.ALIAS, credentials.privateKey, INTERNAL_PASSWORD, arrayOf(credentials.certificate))
            }
            return ByteArrayOutputStream().also { store.store(it, INTERNAL_PASSWORD) }.toByteArray()
        }

        fun pem(der: ByteArray) =
            "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END CERTIFICATE-----\n"
    }
}
