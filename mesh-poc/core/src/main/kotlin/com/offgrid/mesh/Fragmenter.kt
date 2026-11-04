package com.offgrid.mesh

import java.nio.ByteBuffer

/**
 * Link-layer fragmentation. BLE writes are limited to MTU-3 bytes (20 by default, ~512 after
 * negotiation) and Wi-Fi Aware discovery messages to ~255 bytes, so every encoded [Packet] is
 * split into frames: `[frameId u32][index u16][count u16][chunk]`.
 */
object Fragmenter {
    const val HEADER_SIZE = 8

    fun split(data: ByteArray, maxFrameSize: Int, frameId: Int): List<ByteArray> {
        require(maxFrameSize > HEADER_SIZE) { "maxFrameSize must exceed $HEADER_SIZE" }
        val chunk = maxFrameSize - HEADER_SIZE
        val count = maxOf(1, (data.size + chunk - 1) / chunk)
        require(count <= 0xFFFF) { "too many fragments" }
        return (0 until count).map { i ->
            val start = i * chunk
            val len = minOf(data.size, start + chunk) - start
            ByteBuffer.allocate(HEADER_SIZE + len)
                .putInt(frameId)
                .putShort(i.toShort())
                .putShort(count.toShort())
                .put(data, start, len)
                .array()
        }
    }
}

/** Reassembles frames per link. Not thread-safe; owned by the node's executor. */
class Reassembler(
    private val clock: () -> Long,
    private val timeoutMs: Long = 30_000,
    private val maxPending: Int = 64,
) {
    private class Partial(val count: Int, val createdAt: Long) {
        val parts = arrayOfNulls<ByteArray>(count)
        var received = 0
    }

    private val pending = LinkedHashMap<Pair<String, Int>, Partial>()

    fun accept(link: String, frame: ByteArray): ByteArray? {
        if (frame.size < Fragmenter.HEADER_SIZE) return null
        val b = ByteBuffer.wrap(frame)
        val frameId = b.int
        val index = b.short.toInt() and 0xFFFF
        val count = b.short.toInt() and 0xFFFF
        if (count == 0 || index >= count) return null
        val body = ByteArray(b.remaining()).also { b.get(it) }
        if (count == 1) return body

        evict()
        val key = link to frameId
        val p = pending.getOrPut(key) { Partial(count, clock()) }
        if (p.count != count) {
            pending.remove(key)
            return null
        }
        if (p.parts[index] == null) {
            p.parts[index] = body
            p.received++
        }
        if (p.received < count) return null
        pending.remove(key)
        val out = ByteArray(p.parts.sumOf { it!!.size })
        var off = 0
        for (part in p.parts) {
            part!!.copyInto(out, off)
            off += part.size
        }
        return out
    }

    private fun evict() {
        val now = clock()
        pending.values.removeAll { now - it.createdAt > timeoutMs }
        while (pending.size >= maxPending) pending.remove(pending.keys.first())
    }
}
