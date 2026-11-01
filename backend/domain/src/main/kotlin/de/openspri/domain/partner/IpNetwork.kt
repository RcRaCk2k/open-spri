package de.openspri.domain.partner

import java.math.BigInteger
import java.net.InetAddress

/**
 * An IPv4 or IPv6 network in CIDR notation, e.g. `203.0.113.10/32` or `2001:db8::/48`.
 * A plain address without prefix means a single host.
 */
class IpNetwork private constructor(private val network: ByteArray, val prefixLength: Int) {

    operator fun contains(address: InetAddress): Boolean {
        val candidate = address.address
        if (candidate.size != network.size) return false
        val bits = network.size * 8
        val mask = if (prefixLength == 0) BigInteger.ZERO else BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
            .shiftRight(bits - prefixLength).shiftLeft(bits - prefixLength)
        return BigInteger(1, candidate).and(mask) == BigInteger(1, network).and(mask)
    }

    override fun toString(): String = "${InetAddress.getByAddress(network).hostAddress}/$prefixLength"

    override fun equals(other: Any?): Boolean = other is IpNetwork && toString() == other.toString()

    override fun hashCode(): Int = toString().hashCode()

    companion object {
        private val LITERAL = Regex("""[0-9a-fA-F.:]+""")

        /** Parses CIDR notation. Only IP literals are accepted, never host names (no DNS lookups). */
        fun parse(cidr: String): IpNetwork {
            val (host, prefix) = cidr.trim().split('/', limit = 2).let { it[0] to it.getOrNull(1) }
            require(LITERAL.matches(host)) { "Not an IP address: '$host'" }
            val address = InetAddress.getByName(host).address
            val length = prefix?.toIntOrNull() ?: (address.size * 8)
            require(length in 0..address.size * 8) { "Invalid prefix length in '$cidr'" }
            return IpNetwork(address, length)
        }
    }
}
