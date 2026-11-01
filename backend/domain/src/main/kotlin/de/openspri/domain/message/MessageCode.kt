package de.openspri.domain.message

import de.openspri.domain.BusinessCase

/**
 * One entry of the message code table (Anlage 5, maintained online by the AK S&P).
 * The table is shared with WITA and WBCI. All values are kept as published; only the S/PRI values are
 * evaluated. Unknown values (e.g. WITA business cases) are ignored for checks but preserved for display.
 *
 * @property businessCases values of the column "Geschäftsfall", e.g. NEU, EST, REK (complaint), DIAG-GET
 * @property localAddition true for codes that are used by the KFT test case list but missing in the
 *   AK table (docs/spec-abweichungen.md, SA-001)
 */
data class MessageCode(
    val code: String,
    val text: String,
    val messageTypes: Set<String>,
    val businessCases: Set<String>,
    val productGroups: Set<String>,
    val interfaces: Set<String>,
    val validFrom: String? = null,
    val localAddition: Boolean = false,
) {
    /** Message types of S/PRI V4.4 this code is defined for (WITA-only types like FAM are dropped). */
    val spriMessageTypes: Set<MessageType> get() = messageTypes.mapNotNull { MessageType.fromCode(it) }.toSet()

    /** Business cases of S/PRI this code is defined for (REK and Storno belong to the business case itself). */
    val spriBusinessCases: Set<BusinessCase>
        get() = businessCases.mapNotNull { value -> BusinessCase.entries.firstOrNull { it.code == value } }.toSet()
}

/** Result of checking whether a code may be used in a message. */
sealed interface CodeCheck {
    data object Allowed : CodeCheck
    data class Rejected(val reason: String) : CodeCheck
}

/**
 * The active, versioned message code table.
 *
 * @property sha256 checksum of the imported file; identifies the version
 */
class MessageCodeCatalog(
    val source: String,
    val sha256: String,
    codes: Collection<MessageCode>,
) {
    val codes: Map<String, MessageCode> = (codes + LocalMessageCodes.ADDITIONS.filter { add -> codes.none { it.code == add.code } })
        .map { code ->
            LocalMessageCodes.EXTENSIONS[code.code]?.let { extra ->
                // an empty list means "all business cases" and must stay unrestricted
                val businessCases = if (code.businessCases.isEmpty()) emptySet() else code.businessCases + extra.businessCases
                code.copy(messageTypes = code.messageTypes + extra.messageTypes, businessCases = businessCases)
            } ?: code
        }
        .associateBy { it.code }

    fun find(code: String): MessageCode? = codes[code]

    /**
     * R-13: a code must exist and be defined for the message type. If the table restricts the code to
     * certain S/PRI business cases, [businessCase] must be one of them. Complaints (EST with Reklamation)
     * are listed as REK in the table, cancellations as Storno; both are accepted for their base case.
     */
    fun check(code: String, type: MessageType, businessCase: BusinessCase?): CodeCheck {
        val entry = find(code) ?: return CodeCheck.Rejected("Meldungscode $code ist nicht in der Meldungscodetabelle enthalten")
        if (type !in entry.spriMessageTypes) {
            return CodeCheck.Rejected("Meldungscode $code ist für ${type.code} nicht vorgesehen (nur ${entry.messageTypes.joinToString()})")
        }
        if (businessCase != null && entry.spriBusinessCases.isNotEmpty() && businessCase !in entry.spriBusinessCases) {
            val viaAlias = (businessCase == BusinessCase.EST && "REK" in entry.businessCases)
            if (!viaAlias) {
                return CodeCheck.Rejected(
                    "Meldungscode $code ist für ${businessCase.code} nicht vorgesehen (nur ${entry.spriBusinessCases.joinToString { it.code }})",
                )
            }
        }
        return CodeCheck.Allowed
    }
}

/** Versions of the message code table. Implemented by module persistence. */
interface MessageCodeStore {
    /** Imports a table file (AK export or neutral CSV) and makes it the active version. */
    fun importAndActivate(content: ByteArray, source: String): MessageCodeCatalog

    fun active(): MessageCodeCatalog?
}

/** Codes used by the KFT test case list (23.05.2025) but missing in the AK table (SA-001, OP-11). */
object LocalMessageCodes {
    private const val NOTE = "Lokale Ergänzung: in der KFT-Testfallliste verwendet, in der AK-Meldungscodetabelle nicht enthalten (SA-001)"

    val ADDITIONS = listOf(
        MessageCode("1089", NOTE, setOf("ABBM"), setOf("PV"), setOf("FTTH", "FTTB", "FTTC"), setOf("S/PRI"), localAddition = true),
        MessageCode("7501", NOTE, setOf("ABBM"), setOf("DIAG-GET", "DIAG-SET"), setOf("FTTH", "FTTB", "FTTC"), setOf("S/PRI"), localAddition = true),
    )

    data class Extension(val messageTypes: Set<String>, val businessCases: Set<String>)

    /**
     * Additions to existing codes because the KFT test case list uses them that way (SA-008):
     * 6050 is listed for TAM in EST only, the test cases S.1.1.08 / S.1.2.4 (NEU) expect it in MTAM.
     * MTAM exists in NEU, LAE and PV (specification 7.1).
     */
    val EXTENSIONS: Map<String, Extension> = mapOf("6050" to Extension(setOf("MTAM"), setOf("NEU", "LAE", "PV")))
}
