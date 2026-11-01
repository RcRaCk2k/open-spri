package de.openspri.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessCaseTest {

    @Test
    fun `codes match the specification`() {
        assertEquals(
            listOf("NEU", "KUE-AG", "KUE-LE", "AEN-LMAE", "LAE", "PV", "EST", "DIAG-GET", "DIAG-SET"),
            BusinessCase.entries.map { it.code },
        )
        assertEquals(BusinessCase.AEN_LMAE, BusinessCase.fromCode("AEN-LMAE"))
        assertThrows<IllegalArgumentException> { BusinessCase.fromCode("PGW") }
    }

    @Test
    fun `change indicators follow chapter 4_1`() {
        assertTrue(BusinessCase.NEU.allows(ChangeIndicator.TERMINVERSCHIEBUNG))
        assertFalse(BusinessCase.AEN_LMAE.allows(ChangeIndicator.TERMINVERSCHIEBUNG))
        assertTrue(BusinessCase.EST.allows(ChangeIndicator.REKLAMATION))
        assertFalse(BusinessCase.KUE_LE.allows(ChangeIndicator.STORNO))
    }

    @Test
    fun `diagnosis has no change indicator`() {
        assertTrue(BusinessCase.DIAG_GET.allows(null))
        assertFalse(BusinessCase.DIAG_SET.allows(ChangeIndicator.STANDARD))
    }
}
