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
}
