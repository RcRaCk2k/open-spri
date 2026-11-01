package de.openspri.domain

/**
 * Change indicator (Änderungskennzeichen) of an order, S/PRI specification V4.4, chapter 4.1.
 * Storno and Terminverschiebung reuse the external order number of the original order.
 */
enum class ChangeIndicator(val code: String) {
    STANDARD("Standard"),
    STORNO("Storno"),
    TERMINVERSCHIEBUNG("Terminverschiebung"),
    REKLAMATION("Reklamation"),
    ;
}

/**
 * Business cases (Geschäftsfälle) supported by S/PRI V4.4, chapter 4.1.
 * Produktgruppenwechsel and Verbundleistung are not supported by the specification and therefore not modelled.
 *
 * @property code abbreviation used in the specification, the KFT test case list and the message code table
 * @property wireCode value of `geschaeftsfall` in messages (XSD GeschaeftsfallMeldungType); differs for diagnosis
 * @property optional optional business case according to the specification (chapter 6)
 * @property changeIndicators change indicators allowed for this business case
 */
enum class BusinessCase(
    val code: String,
    val wireCode: String,
    val optional: Boolean,
    val changeIndicators: Set<ChangeIndicator>,
) {
    NEU("NEU", "NEU", false, setOf(ChangeIndicator.STANDARD, ChangeIndicator.STORNO, ChangeIndicator.TERMINVERSCHIEBUNG)),
    KUE_AG("KUE-AG", "KUE-AG", false, setOf(ChangeIndicator.STANDARD, ChangeIndicator.STORNO, ChangeIndicator.TERMINVERSCHIEBUNG)),
    KUE_LE("KUE-LE", "KUE-LE", false, setOf(ChangeIndicator.STANDARD)),
    AEN_LMAE("AEN-LMAE", "AEN-LMAE", false, setOf(ChangeIndicator.STANDARD, ChangeIndicator.STORNO)),
    LAE("LAE", "LAE", false, setOf(ChangeIndicator.STANDARD, ChangeIndicator.STORNO, ChangeIndicator.TERMINVERSCHIEBUNG)),
    PV("PV", "PV", false, setOf(ChangeIndicator.STANDARD, ChangeIndicator.STORNO, ChangeIndicator.TERMINVERSCHIEBUNG)),
    EST("EST", "EST", true, setOf(ChangeIndicator.STANDARD, ChangeIndicator.REKLAMATION, ChangeIndicator.STORNO)),
    DIAG_GET("DIAG-GET", "GET", true, emptySet()),
    DIAG_SET("DIAG-SET", "SET", true, emptySet()),
    ;

    /** Diagnosis orders carry no change indicator (specification chapter 6.2). */
    fun allows(indicator: ChangeIndicator?): Boolean =
        if (changeIndicators.isEmpty()) indicator == null else indicator in changeIndicators

    companion object {
        fun fromCode(code: String): BusinessCase =
            entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("Unknown business case '$code'")

        fun fromWireCode(code: String): BusinessCase =
            entries.firstOrNull { it.wireCode == code } ?: throw IllegalArgumentException("Unknown business case '$code'")
    }
}
