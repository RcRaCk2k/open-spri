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

    /** CIDR notation, IPv6 in the canonical short form (RFC 5952), e.g. `2001:db8::1/128`. */
    override fun toString(): String = "${format(network)}/$prefixLength"

    /** The address alone for a single host (`/32`, `/128`), otherwise the CIDR notation. */
    fun toHostOrCidr(): String = if (prefixLength == network.size * 8) format(network) else toString()

    override fun equals(other: Any?): Boolean = other is IpNetwork && toString() == other.toString()

    override fun hashCode(): Int = toString().hashCode()

    companion object {
        /** Canonical text of an address or network (see [toHostOrCidr]); text that is no address stays unchanged. */
        fun normalize(text: String): String = runCatching { parse(text).toHostOrCidr() }.getOrDefault(text)

        /** Text form of an address: IPv4 dotted, IPv6 as RFC 5952 (lower case, longest zero run as `::`). */
        fun format(address: InetAddress): String = format(address.address)

        private fun format(bytes: ByteArray): String {
            if (bytes.size == 4) return InetAddress.getByAddress(bytes).hostAddress
            val groups = IntArray(8) { ((bytes[2 * it].toInt() and 0xff) shl 8) or (bytes[2 * it + 1].toInt() and 0xff) }
            // longest run of at least two zero groups; the first one wins a tie (RFC 5952, 4.2.3)
            var bestStart = -1
            var bestLength = 1
            var i = 0
            while (i < 8) {
                if (groups[i] == 0) {
                    val start = i
                    while (i < 8 && groups[i] == 0) i++
                    if (i - start > bestLength) { bestStart = start; bestLength = i - start }
                } else {
                    i++
                }
            }
            val hex = groups.map { Integer.toHexString(it) }
            if (bestStart < 0) return hex.joinToString(":")
            return hex.subList(0, bestStart).joinToString(":") + "::" + hex.subList(bestStart + bestLength, 8).joinToString(":")
        }

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
