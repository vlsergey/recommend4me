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
        bits = prefix?.toInt() ?: (base.size * 8)
        require(bits in 0..base.size * 8) { "Not a network: $cidr" }
    }

    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != base.size) return false
        for (i in 0 until bits / 8) if (bytes[i] != base[i]) return false
        val rest = bits % 8
        if (rest == 0) return true
        val mask = (0xff shl (8 - rest)) and 0xff
        return (bytes[bits / 8].toInt() and mask) == (base[bits / 8].toInt() and mask)
    }
}

/**
 * Who may reach the application, by address: this machine itself, and the [networks] the personal
 * configuration names. No one else — the local network neither, unless named: there is no password.
 */
class Addresses(private val networks: List<Network>) {

    fun allowed(ip: String?): Boolean {
        val address = parse(ip) ?: return false
        return address.isLoopbackAddress || networks.any { it.contains(address) }
    }

    /** The addresses of this machine's adapters inside the [networks], as they are now. */
    fun own(): List<InetAddress> =
        NetworkInterface.networkInterfaces().toList()
            .filter { runCatching { it.isUp }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filter { a -> !a.isLoopbackAddress && networks.any { it.contains(a) } }
            // The four-number address first: the one a person types
            .sortedBy { it is java.net.Inet6Address }

    companion object {
        /**
         * The address as written, without brackets and zone; a dual-stack socket reports an address of
         * the fourth family as "::ffff:100.64.0.1". Null for what is not an address literal — no name
         * is looked up.
         */
        fun parse(ip: String?): InetAddress? {
            val h = ip.orEmpty().trim('[', ']').lowercase().substringBefore('%').removePrefix("::ffff:")
            if (h.isEmpty()) return null
            val literal = h.all { it.isDigit() || it == '.' } || (h.contains(':') && h.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' })
            return if (literal) runCatching { InetAddress.getByName(h) }.getOrNull() else null
        }
    }
}
