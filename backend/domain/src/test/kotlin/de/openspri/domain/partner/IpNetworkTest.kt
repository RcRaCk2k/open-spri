package de.openspri.domain.partner

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IpNetworkTest {

    private fun ip(value: String) = InetAddress.getByName(value)

    @Test
    fun `single host`() {
        val network = IpNetwork.parse("203.0.113.10")
        assertTrue(ip("203.0.113.10") in network)
        assertFalse(ip("203.0.113.11") in network)
        assertEquals("203.0.113.10/32", network.toString())
    }

    @Test
    fun `IPv4 range`() {
        val network = IpNetwork.parse("198.51.100.0/28")
        assertTrue(ip("198.51.100.15") in network)
        assertFalse(ip("198.51.100.16") in network)
    }

    @Test
    fun `IPv6 range and family mismatch`() {
        val network = IpNetwork.parse("2001:db8::/48")
        assertTrue(ip("2001:db8:0:ffff::1") in network)
        assertFalse(ip("2001:db9::1") in network)
        assertFalse(ip("198.51.100.1") in network)
    }

    @Test
    fun `rejects host names and invalid prefixes`() {
        assertThrows<IllegalArgumentException> { IpNetwork.parse("partner.example.de") }
        assertThrows<IllegalArgumentException> { IpNetwork.parse("10.0.0.0/33") }
    }

    @Test
    fun `IPv6 is written in the short form of RFC 5952`() {
        mapOf(
            "2a04:df80:0:0:0:ffff:2d99:b308" to "2a04:df80::ffff:2d99:b308",
            "2001:DB8:0000:0000:0000:0000:0000:0001" to "2001:db8::1",
            "2001:db8:0:1:0:0:0:1" to "2001:db8:0:1::1",        // longest zero run
            "2001:db8:0:0:1:0:0:1" to "2001:db8::1:0:0:1",      // tie: the first run
            "2001:db8:1:2:3:4:0:5" to "2001:db8:1:2:3:4:0:5",   // a single zero group stays
            "0:0:0:0:0:0:0:1" to "::1",
            "0:0:0:0:0:0:0:0" to "::",
            "fe80:0:0:0:0:0:0:0" to "fe80::",
        ).forEach { (input, expected) -> assertEquals(expected, IpNetwork.normalize(input), input) }
        assertEquals("2001:db8::/48", IpNetwork.parse("2001:0db8:0000::/48").toString())
        assertEquals("2001:db8::/48", IpNetwork.normalize("2001:db8:0:0::/48"), "networks keep their prefix")
        assertEquals("203.0.113.10", IpNetwork.normalize("203.0.113.10/32"))
        assertEquals("kein ip", IpNetwork.normalize("kein ip"))
    }
}
