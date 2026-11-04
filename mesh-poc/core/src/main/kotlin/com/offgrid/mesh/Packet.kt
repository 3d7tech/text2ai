package com.offgrid.mesh

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Mesh-layer packet. Wire format (big-endian, 46-byte header):
 *
 * ```
 * 0      version   u8
 * 1      type      u8
 * 2      ttl       u8   decremented at every relay; dropped at 0
 * 3      hops      u8   incremented at every relay (for display / routing metrics)
 * 4..19  id        16B  random, used for de-duplication
 * 20..27 src       8B   NodeId
 * 28..35 dst       8B   NodeId (all 0xFF = broadcast)
 * 36..43 timestamp u64  ms since epoch, set by origin
 * 44..45 len       u16
 * 46..   payload
 * ```
 */
class Packet(
    val type: Int,
    val ttl: Int,
    val hops: Int,
    val id: PacketId,
    val src: NodeId,
    val dst: NodeId,
    val timestamp: Long,
    val payload: ByteArray,
) {
    init {
        require(type in 0..255) { "type out of range" }
        require(ttl in 0..255) { "ttl out of range" }
        require(hops in 0..255) { "hops out of range" }
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
    }

    fun relayed(): Packet = Packet(type, ttl - 1, minOf(hops + 1, 255), id, src, dst, timestamp, payload)

    fun encode(): ByteArray = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        .put(VERSION)
        .put(type.toByte())
        .put(ttl.toByte())
        .put(hops.toByte())
        .putLong(id.hi).putLong(id.lo)
        .putLong(src.raw)
        .putLong(dst.raw)
        .putLong(timestamp)
        .putShort(payload.size.toShort())
        .put(payload)
        .array()

    override fun equals(other: Any?): Boolean = other is Packet && other.id == id
    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val VERSION: Byte = 1
        const val HEADER_SIZE = 46
        const val MAX_PAYLOAD = 4096
        const val TYPE_CHAT = 1

        fun decode(bytes: ByteArray): Packet? {
            if (bytes.size < HEADER_SIZE) return null
            val b = ByteBuffer.wrap(bytes)
            if (b.get() != VERSION) return null
            val type = b.get().toInt() and 0xFF
            val ttl = b.get().toInt() and 0xFF
            val hops = b.get().toInt() and 0xFF
            val id = PacketId(b.long, b.long)
            val src = NodeId(b.long)
            val dst = NodeId(b.long)
            val ts = b.long
            val len = b.short.toInt() and 0xFFFF
            if (len > MAX_PAYLOAD || b.remaining() != len) return null
            val payload = ByteArray(len).also { b.get(it) }
            return Packet(type, ttl, hops, id, src, dst, ts, payload)
        }
    }
}

/** Payload of a [Packet.TYPE_CHAT] packet: `[nickLen u8][nick utf8][text utf8]`. */
data class ChatPayload(val nick: String, val text: String) {
    fun encode(): ByteArray {
        val n = nick.take(MAX_NICK_CHARS).toByteArray(UTF_8)
        val t = text.toByteArray(UTF_8)
        return ByteBuffer.allocate(1 + n.size + t.size).put(n.size.toByte()).put(n).put(t).array()
    }

    companion object {
        const val MAX_NICK_CHARS = 24

        fun decode(p: ByteArray): ChatPayload? {
            if (p.isEmpty()) return null
            val n = p[0].toInt() and 0xFF
            if (1 + n > p.size) return null
            return ChatPayload(
                String(p, 1, n, UTF_8),
                String(p, 1 + n, p.size - 1 - n, UTF_8),
            )
        }
    }
}

/** A chat message as surfaced to the UI. */
data class ChatMessage(
    val id: PacketId,
    val from: NodeId,
    val nick: String,
    val to: NodeId,
    val text: String,
    val timestamp: Long,
    val hops: Int,
    val outgoing: Boolean,
) {
    val isDirect: Boolean get() = !to.isBroadcast
}
