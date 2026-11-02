package de.openspri.domain.order

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import java.time.LocalDate

/**
 * Rules of our own orders that may be overridden for the KFT negative test cases (ADR-0008, scope
 * `orders:override`). XSD, Anlage 1 and the lifecycle are never overridden.
 *
 * @property code name in the REST API (`?override=`)
 * @property prefix start of the finding the rule produces
 */
enum class RuleOverride(val code: String, val prefix: String) {
    /** R-05: execution date 180 days or more ahead (KFT Z.2.2.01/Z.2.2.02, ABBM 1003). */
    DATE_LIMIT("R-05", "R-05"),
    /** Geschäftsfallart does not fit the business case (KFT Z.5.2.01, ABBM 1102). */
    BUSINESS_CASE_KIND("GESCHAEFTSFALLART", "Unzulässiger Geschäftsfall"),
    /** A new order with "Storno" instead of "Standard" (KFT Z.4.2.01, ABBM 1007). */
    CHANGE_WITHOUT_ORDER("AENDERUNGSKENNZEICHEN", "Änderungskennzeichen ohne Ursprungsauftrag"),
    ;

    companion object {
        fun fromCode(code: String) = entries.firstOrNull { it.code == code.trim().uppercase() }
            ?: throw IllegalArgumentException("override: erlaubt sind ${entries.joinToString { it.code }}")
    }
}

/**
 * Content rules of AUFGABE chapter 5.2 that are independent of the message sequence. They return findings
 * (texts with rule reference); what happens with a finding depends on the role: as Leistungserbringer it is
 * the basis for an ABBM, as Auftraggeber it is a check of our own order before sending.
 */
object OrderRules {

    /** R-15: CountryCode (3) . CarrierCode (1-6) . LineCode (1-10), upper case letters and digits only. */
    private val LINE_ID = Regex("""[A-Z0-9]{3}\.[A-Z0-9]{1,6}\.[A-Z0-9]{1,10}""")
    const val MAX_DAYS_AHEAD = 180L
    const val MAX_BRACKET_SIZE = 20

    fun isValidLineId(value: String) = LINE_ID.matches(value)

    /** Checks of a new order (Standard) and of its requests. */
    fun check(data: OrderData, today: LocalDate, original: OrderData? = null): List<String> = buildList {
        data.lineId?.let { if (!isValidLineId(it)) add("R-15: LineID '$it' entspricht nicht der Bildungsregel Land.Carrier.Leitung") }

        // R-05: execution date less than 180 days ahead; exception: TV in a Konnektivitätsauftrag with VorabstimmungID
        val date = if (data.changeIndicator == ChangeIndicator.TERMINVERSCHIEBUNG) data.newDate else data.desiredDate
        val exempt = data.changeIndicator == ChangeIndicator.TERMINVERSCHIEBUNG && (original?.connectivity == true) && data.preAgreementId != null
        if (date != null && !exempt && date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            add("R-05: Wunschtermin $date liegt mehr als $MAX_DAYS_AHEAD Tage in der Zukunft")
        }

        // R-02: Terminverschiebung needs a different date, except for the automatic connection identification
        if (data.changeIndicator == ChangeIndicator.TERMINVERSCHIEBUNG) {
            if (data.newDate == null) add("R-02: Terminverschiebung ohne neuen Termin (terminNeu)")
            val previous = original?.desiredDate
            if (data.newDate != null && data.newDate == previous && data.ontSerial == null) {
                add("R-02: Der neue Termin entspricht dem bisherigen Wunschtermin")
            }
        }

        // R-12: Kenner
        if (data.couplingId != null && data.projectId == null) add("R-12: Kopplungskenner nur in Verbindung mit einem Projektkenner")
        if (data.bracketId != null) {
            if ((data.bracketSize ?: 0) > MAX_BRACKET_SIZE) add("R-12: Eine Auftragsklammer umfasst höchstens $MAX_BRACKET_SIZE Aufträge")
            if (data.businessCase == BusinessCase.KUE_AG) add("R-12: Kündigungen dürfen nicht geklammert werden")
        }

        // Geschäftsfall and Geschäftsfallart must fit (KFT Z.5.2.01/Z.12.2.01: "Diagnose" instead of "Änderung", ABBM 1102)
        data.declaredKind?.let { kind ->
            if (kind != data.businessCase.kind) add("Unzulässiger Geschäftsfall: Geschäftsfallart $kind passt nicht zu ${data.businessCase.code} (erwartet ${data.businessCase.kind})")
        }

        // R-11 (formal part): a Besteller needs both numbers
        if ((data.ordererClientNumber == null) != (data.ordererServiceNumber == null)) {
            add("R-11: Bestellung im Namen Dritter braucht Auftraggeber- und Leistungsnummer des Bestellers")
        }
    }

    /** R-10 key: LineID, otherwise HomeID, otherwise the normalized address of StandortA. */
    fun infrastructureKey(lineId: String?, homeId: String?, address: List<String?>): String? = when {
        !lineId.isNullOrBlank() -> "LINE:${lineId.trim()}"
        !homeId.isNullOrBlank() -> "HOME:${homeId.trim().uppercase()}"
        address.all { !it.isNullOrBlank() } && address.isNotEmpty() ->
            "ADDR:" + address.joinToString("|") { it!!.trim().lowercase().replace(Regex("\\s+"), " ") }
        else -> null
    }
}
