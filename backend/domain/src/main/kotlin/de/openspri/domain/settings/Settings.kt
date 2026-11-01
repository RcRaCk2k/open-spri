package de.openspri.domain.settings

import java.time.Instant

/** How the VorabstimmungsID of a provider change is obtained (AUFGABE 5.8, D-06). */
enum class WbciMode {
    /** From a WBCI service provider; entered manually or via REST. */
    SERVICE_PROVIDER,
    /** From an own WBCI interface (adapter, not part of v1). */
    OWN_INTERFACE,
    /** No provider change. Note: PV is mandatory for the certification. */
    NONE,
}

/**
 * Company data and options, captured by the setup wizard (AUFGABE 5.10) and changeable in the settings.
 *
 * @property carrierCode ITU Carrier Code (ICC) - identifies us towards all partners
 * @property publicSpriEndpoint our endpoint as written into the WSDL for partners
 */
data class CompanySettings(
    val name: String,
    val street: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val carrierCode: String,
    val contactEmail: String,
    val roleAuftraggeber: Boolean,
    val roleLeistungserbringer: Boolean,
    val estEnabled: Boolean = true,
    val diagEnabled: Boolean = true,
    val connectivityEnabled: Boolean = true,
    val thirdPartyOrdersEnabled: Boolean = true,
    val wbciMode: WbciMode = WbciMode.SERVICE_PROVIDER,
    val publicSpriEndpoint: String? = null,
    /** Our outbound IP addresses; partners allowlist them (AUFGABE 7.6, security document 2.6). */
    val outboundAddresses: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "Firmenname fehlt" }
        require(CARRIER_CODE.matches(carrierCode)) { "ICC: 1–6 Großbuchstaben oder Ziffern (ITU M.1400)" }
        require(contactEmail.contains('@')) { "Kontakt-E-Mail ungültig" }
        require(roleAuftraggeber || roleLeistungserbringer) { "Mindestens eine Rolle (Auftraggeber oder Leistungserbringer) wählen" }
    }

    companion object {
        /** ITU M.1400 carrier code: up to 6 characters (LineID document 3.1.1). */
        val CARRIER_CODE = Regex("""[A-Z0-9]{1,6}""")
    }
}

interface SettingsRepository {
    fun load(): CompanySettings?
    fun save(settings: CompanySettings)
    fun setupCompleted(): Boolean
    fun markSetupCompleted(at: Instant)
}

/** Our WS-Security key, stored encrypted (AES-GCM with the master key from the environment). */
data class StoredSigningKey(
    val id: Long?,
    val encryptedPkcs12: ByteArray,
    val subject: String,
    val serialNumber: String,
    val notAfter: Instant,
    val createdBy: String,
    val createdAt: Instant,
)

interface SigningKeyRepository {
    fun active(): StoredSigningKey?

    /** Stores [key] and makes it the active one; older keys are kept for traceability. */
    fun activate(key: StoredSigningKey): StoredSigningKey
}
