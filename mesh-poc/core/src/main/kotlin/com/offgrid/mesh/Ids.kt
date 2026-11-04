package com.offgrid.mesh

import java.security.SecureRandom

private val rng = SecureRandom()

/** 64-bit node address. In a real build this becomes a hash of the node's public key. */
@JvmInline
value class NodeId(val raw: Long) {
    val isBroadcast: Boolean get() = raw == BROADCAST_RAW

    fun hex(): String = java.lang.Long.toUnsignedString(raw, 16).padStart(16, '0')

    override fun toString(): String = hex()

    companion object {
        private const val BROADCAST_RAW = -1L
        val BROADCAST = NodeId(BROADCAST_RAW)

        fun random(): NodeId {
            while (true) {
                val v = rng.nextLong()
                if (v != BROADCAST_RAW && v != 0L) return NodeId(v)
            }
        }

        fun parse(hex: String): NodeId = NodeId(java.lang.Long.parseUnsignedLong(hex, 16))
    }
}

/** 128-bit globally unique packet id, used for de-duplication across the mesh. */
data class PacketId(val hi: Long, val lo: Long) {
    override fun toString(): String =
        java.lang.Long.toUnsignedString(hi, 16).padStart(16, '0') +
            java.lang.Long.toUnsignedString(lo, 16).padStart(16, '0')

    companion object {
        fun random(): PacketId = PacketId(rng.nextLong(), rng.nextLong())
    }
}
