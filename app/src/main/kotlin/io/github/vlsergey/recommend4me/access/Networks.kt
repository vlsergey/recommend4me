package io.github.vlsergey.recommend4me.access

import java.net.InetAddress
import java.net.NetworkInterface

/** A network written as CIDR: `100.64.0.0/10`, `fd7a:115c:a1e0::/48`. */
class Network(cidr: String) {
    private val base: ByteArray
    private val bits: Int

    init {
        val (address, prefix) = cidr.trim().split('/', limit = 2).let { it[0] to it.getOrNull(1) }
        base = InetAddress.getByName(address).address
        bits = prefix?.toInt() ?: (base.size * Byte.SIZE_BITS)
        require(bits in 0..base.size * Byte.SIZE_BITS) { "Not a network: $cidr" }
    }

    /**
     * Whether [address] is of this network: of the same family, its whole bytes of the prefix equal, and the prefix's
     * bits of the byte the prefix ends within.
     */
    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        val whole = bits / Byte.SIZE_BITS
        val rest = bits % Byte.SIZE_BITS
        val mask = (BYTE shl (Byte.SIZE_BITS - rest)) and BYTE
        return bytes.size == base.size &&
            (0 until whole).all { bytes[it] == base[it] } &&
            (rest == 0 || (bytes[whole].toInt() and mask) == (base[whole].toInt() and mask))
    }

    private companion object {
        /** The bits of a byte, all set: a byte read as an unsigned number. */
        const val BYTE = 0xff
    }
}

/**
 * Who may reach the application, by address: this machine itself, and the [networks] the personal configuration names.
 * No one else — the local network neither, unless named: there is no password.
 */
class Addresses(private val networks: List<Network>) {

    fun allowed(ip: String?): Boolean {
        val address = parse(ip) ?: return false
        return address.isLoopbackAddress || networks.any { it.contains(address) }
    }

    /** The addresses of this machine's adapters inside the [networks], as they are now. */
    fun own(): List<InetAddress> =
        NetworkInterface.networkInterfaces()
            .toList()
            .filter { runCatching { it.isUp }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filter { a -> !a.isLoopbackAddress && networks.any { it.contains(a) } }
            // The four-number address first: the one a person types
            .sortedBy { it is java.net.Inet6Address }

    companion object {
        /**
         * The address as written, without brackets and zone; a dual-stack socket reports an address of the fourth
         * family as "::ffff:100.64.0.1". Null for what is not an address literal — no name is looked up.
         */
        fun parse(ip: String?): InetAddress? {
            val h = ip.orEmpty().trim('[', ']').lowercase().substringBefore('%').removePrefix("::ffff:")
            if (h.isEmpty()) return null
            val literal =
                h.all { it.isDigit() || it == '.' } ||
                    (h.contains(':') && h.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' })
            return if (literal) runCatching { InetAddress.getByName(h) }.getOrNull() else null
        }
    }
}
