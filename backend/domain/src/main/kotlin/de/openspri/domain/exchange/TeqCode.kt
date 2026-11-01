package de.openspri.domain.exchange

/**
 * Codes of the technical acknowledgement (TEQ). Texts as in the AK S&P message code table
 * (Anlage 5, export 2026-11-01). Which check produces which code is documented in
 * docs/AUFGABE-SPRI.md, chapter 5.3, and docs/spec-abweichungen.md (SA-002).
 *
 * For security reasons a negative TEQ only carries the error number, never details
 * (specification 11.2.3.1).
 */
enum class TeqCode(val code: String, val text: String) {
    OK("OK", "OK."),
    FUNCTION_NOT_SUPPORTED("0990", "Funktion aktuell nicht unterstützt, Freigabe wird gesondert bekannt gegeben."),
    OVERLOADED("0991", "Die Schnittstelle ist derzeit überlastet, bitte versuchen Sie es zu einem späteren Zeitpunkt erneut."),
    VERSION_INVALID("0993", "Versionsnummer fehlerhaft"),
    STRUCTURE_INVALID("0995", "Datenstruktur unzulässig."),
    TECHNICAL_ERROR("0999", "Technischer Fehler."),
    ;

    val isPositive: Boolean get() = this == OK

    companion object {
        fun fromCode(code: String): TeqCode? = entries.firstOrNull { it.code == code }
    }
}
