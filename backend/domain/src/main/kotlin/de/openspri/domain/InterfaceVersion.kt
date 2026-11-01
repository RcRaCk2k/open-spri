package de.openspri.domain

/**
 * Version of the S/PRI interface as transmitted in every order and message
 * (`majorRelease` / `minorRelease` in the control header).
 *
 * Rules from the S/PRI specification V4.4, chapter 10.4:
 * - Major release: AN 2, digits only, starting with 1.
 * - Minor version: AN 2, digits only, starting with 0.
 * - A major release may require application changes; a minor version is always backwards compatible.
 */
data class InterfaceVersion(val major: Int, val minor: Int) : Comparable<InterfaceVersion> {

    init {
        require(major in 1..99) { "Major release must be between 1 and 99, was $major" }
        require(minor in 0..99) { "Minor version must be between 0 and 99, was $minor" }
    }

    /** True if a message with this version can be processed by an endpoint implementing [other]. */
    fun isCompatibleWith(other: InterfaceVersion): Boolean = major == other.major

    override fun compareTo(other: InterfaceVersion): Int =
        compareValuesBy(this, other, InterfaceVersion::major, InterfaceVersion::minor)

    override fun toString(): String = "$major.$minor"

    companion object {
        /** The S/PRI version implemented by open-spri. */
        val CURRENT = InterfaceVersion(4, 4)

        private val DIGITS = Regex("""\d{1,2}""")

        /** Parses the two header fields as received on the wire. */
        fun parse(majorRelease: String, minorRelease: String): InterfaceVersion {
            require(DIGITS.matches(majorRelease)) { "Major release must be 1-2 digits, was '$majorRelease'" }
            require(DIGITS.matches(minorRelease)) { "Minor version must be 1-2 digits, was '$minorRelease'" }
            return InterfaceVersion(majorRelease.toInt(), minorRelease.toInt())
        }
    }
}
