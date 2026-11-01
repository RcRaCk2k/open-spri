package de.openspri.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InterfaceVersionTest {

    @Test
    fun `current version is 4_4`() {
        assertEquals("4.4", InterfaceVersion.CURRENT.toString())
    }

    @ParameterizedTest
    @CsvSource("4,4,4,4", "04,01,4,1", "10,0,10,0")
    fun `parses header fields`(major: String, minor: String, expectedMajor: Int, expectedMinor: Int) {
        assertEquals(InterfaceVersion(expectedMajor, expectedMinor), InterfaceVersion.parse(major, minor))
    }

    @ParameterizedTest
    @CsvSource("0,0", "a,1", "4,x", "100,0", "4,100", "'',1")
    fun `rejects invalid header fields (spec 10_4)`(major: String, minor: String) {
        assertThrows<IllegalArgumentException> { InterfaceVersion.parse(major, minor) }
    }

    @Test
    fun `only the major release decides compatibility`() {
        assertTrue(InterfaceVersion(4, 0).isCompatibleWith(InterfaceVersion.CURRENT))
        assertFalse(InterfaceVersion(3, 0).isCompatibleWith(InterfaceVersion.CURRENT))
    }
}
