package de.openspri.simulator

/**
 * Special content a test case requires beyond the message sequence, recognized from its description, sequence
 * and hint. A scenario only counts as automated if every variant of its test case is implemented
 * ([implemented]); otherwise it would pass with a standard order and prove nothing.
 */
enum class Variant(val label: String) {
    // order content
    PROJECT("Projektkenner"),
    ORDER_MARKER("Auftragskenner"),
    THIRD_PARTY("Bestellung im Namen Dritter"),
    PRE_AGREEMENT("Vorabstimmung"),
    HOME_ID("HomeID"),
    CONNECTIVITY("Konnektivitätsauftrag"),
    INVALID_FEATURE("Leistungsmerkmal TEST"),
    WRONG_ORDER_KIND("falsche Auftragsart"),
    WRONG_CHANGE_INDICATOR("Änderungskennzeichen Storno statt Standard"),
    WRONG_BUSINESS_CASE("Geschäftsfall Diagnose statt Änderung"),
    INVALID_PRODUCT("Produkt Dummy"),
    FAR_DATE("Wunschtermin mehr als 179 Tage voraus"),
    PROVIDER_CHANGE_LE("Providerwechsel als Leistungserbringer mit dem abgebenden EKP"),

    // message content and transport
    NEW_DATE("ABM mit neuem Termin"),
    TWO_CODES("ABBM mit zwei Meldungspositionen (1312 Freitext)"),
    HINT_CODE("Meldungscode aus dem Hinweis"),
    OVERLOADED("TEQ 0991 und Wiederholung"),
}

object TestCaseVariants {

    /** Variants the scenarios implement; the rest keeps the test case open in the coverage gate. */
    val implemented = setOf(
        Variant.NEW_DATE, Variant.TWO_CODES, Variant.HINT_CODE, Variant.OVERLOADED,
        Variant.PROJECT, Variant.ORDER_MARKER, Variant.THIRD_PARTY, Variant.PRE_AGREEMENT, Variant.HOME_ID, Variant.CONNECTIVITY,
        // "falsche Auftragsart" refers to the Replikat (what the Leistungserbringer offers); the order is a normal
        // Konnektivitätsauftrag, the Leistungserbringer answers ABBM 1510
        Variant.WRONG_ORDER_KIND,
        Variant.PROVIDER_CHANGE_LE,
    )

    /**
     * Faulty orders of the Auftraggeber (negative test cases). As Leistungserbringer the simulator sends them and
     * open-spri must report the matching finding ([expectedFinding]); the Auftraggeber side is not implemented yet.
     */
    val faultyOrders = setOf(Variant.FAR_DATE, Variant.INVALID_PRODUCT, Variant.INVALID_FEATURE, Variant.WRONG_BUSINESS_CASE, Variant.WRONG_CHANGE_INDICATOR)

    /** The finding open-spri as Leistungserbringer reports for a faulty order (basis of the ABBM). */
    fun expectedFinding(variant: Variant): String? = when (variant) {
        Variant.FAR_DATE -> "R-05"
        Variant.INVALID_PRODUCT, Variant.INVALID_FEATURE -> "nicht im Produktkatalog"
        Variant.WRONG_BUSINESS_CASE -> "Unzulässiger Geschäftsfall"
        Variant.WRONG_CHANGE_INDICATOR -> "unzulässiges Änderungskennzeichen"
        else -> null
    }

    /** Konnektivitätsauftrag with owner data ("mit Eigentümerdaten" in the description). */
    fun withOwner(case: KftTestCase) = "mit Eigentümerdaten" in case.description

    private val HINT_CODE = Regex("""Meldungscode (\d{4})|z\.B\. (\d{4})""")

    fun of(case: KftTestCase): Set<Variant> = buildSet {
        val text = case.description + " " + case.hint
        if ("ProjektID" in text) add(Variant.PROJECT)
        if ("Auftragskenner" in text) add(Variant.ORDER_MARKER)
        if ("Dritter" in text) add(Variant.THIRD_PARTY)
        if ("Vorabstimmung" in case.description) add(Variant.PRE_AGREEMENT)
        if ("HomeID" in text) add(Variant.HOME_ID)
        if (case.connectivity) add(Variant.CONNECTIVITY)
        if ("Leistungsmerkmal" in text) add(Variant.INVALID_FEATURE)
        if ("falsche Auftragsart" in text) add(Variant.WRONG_ORDER_KIND)
        if ("Änderungskennzeichen" in case.hint) add(Variant.WRONG_CHANGE_INDICATOR)
        if ("Geschäftsfall \"Diagnose\"" in case.hint) add(Variant.WRONG_BUSINESS_CASE)
        if ("Produkt \"Dummy\"" in case.hint) add(Variant.INVALID_PRODUCT)
        if ("179" in case.description) add(Variant.FAR_DATE)
        if (!case.partnerSheet && "<AKM-PV" in case.sequence) add(Variant.PROVIDER_CHANGE_LE)
        if ("neuer Termin" in case.sequence) add(Variant.NEW_DATE)
        if ("2 zutreffende MC" in case.sequence) add(Variant.TWO_CODES)
        if (hintCode(case) != null) add(Variant.HINT_CODE)
        if ("TEQ(991)" in case.sequence.replace(" ", "")) add(Variant.OVERLOADED)
    }

    /** The code a hint names for the answer of the Leistungserbringer, e.g. 1010 (Auftragsdublette). */
    fun hintCode(case: KftTestCase): String? =
        HINT_CODE.find(case.hint)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.takeIf { "1312" !in case.hint }

    fun missing(case: KftTestCase): Set<Variant> = of(case) - implemented - (if (case.partnerSheet) emptySet() else faultyOrders)
}
