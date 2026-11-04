package com.offgrid.mesh

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Transport-agnostic mesh router.
 *
 * - **Flooding with de-duplication:** every packet carries a random 128-bit id; a node relays a
 *   packet it has not seen before to all neighbors except the one it came from, with ttl-1.
 * - **Multi-radio:** the same packet may arrive over BLE *and* Wi-Fi Aware; the seen-cache
 *   makes that harmless, and whichever radio is faster wins.
 * - **Store-and-forward (delay-tolerant):** recently relayed packets are kept in a "carry"
 *   buffer and replayed to every newly discovered neighbor. A phone that walks from one village
 *   to the next therefore delivers messages even if the two groups were never connected at
 *   the same time.
 *
 * All state is confined to [executor] (use a single-threaded executor).
 */
class MeshNode(
    val id: NodeId,
    nickname: String,
    private val executor: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: Config = Config(),
) : TransportListener {

    data class Config(
        val defaultTtl: Int = 7,
        val seenCacheSize: Int = 8192,
        val carryMax: Int = 256,
        val carryMaxAgeMs: Long = 6 * 60 * 60_000L,
    )

    interface Listener {
        fun onMessage(message: ChatMessage) {}
        fun onNeighborsChanged(neighbors: List<String>) {}
    }

    private data class Neighbor(val transport: Transport, val link: String) {
        override fun toString() = "${transport.name}/$link"
    }

    private class Carried(val packet: Packet, val storedAt: Long)

    @Volatile var nickname: String = nickname.take(ChatPayload.MAX_NICK_CHARS)
        set(value) { field = value.take(ChatPayload.MAX_NICK_CHARS) }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val transports = mutableListOf<Transport>()
    private val neighbors = LinkedHashSet<Neighbor>()
    private val reassemblers = HashMap<Transport, Reassembler>()
    private val carry = ArrayDeque<Carried>()
    private var frameCounter = (Math.random() * Int.MAX_VALUE).toInt()
    private val seen = object : LinkedHashMap<PacketId, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PacketId, Boolean>?) =
            size > config.seenCacheSize
    }

    /** Last known nickname for every node we have heard from. */
    val peers: Map<NodeId, String> get() = peerNames.toMap()
    private val peerNames = java.util.concurrent.ConcurrentHashMap<NodeId, String>()

    @Volatile var neighborSnapshot: List<String> = emptyList()
        private set

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    fun start(radios: List<Transport>) = executor.execute {
        for (t in radios) {
            transports += t
            t.start(this)
        }
    }

    fun stop() = executor.execute {
        transports.forEach { runCatching { it.stop() } }
        transports.clear()
        neighbors.clear()
        reassemblers.clear()
        publishNeighbors()
    }

    fun sendBroadcast(text: String): ChatMessage = originate(NodeId.BROADCAST, text)

    fun sendDirect(to: NodeId, text: String): ChatMessage = originate(to, text)

    private fun originate(dst: NodeId, text: String): ChatMessage {
        val nick = nickname
        val payload = ChatPayload(nick, text).encode()
        require(payload.size <= Packet.MAX_PAYLOAD) { "message too long" }
        val p = Packet(Packet.TYPE_CHAT, config.defaultTtl, 0, PacketId.random(), id, dst, clock(), payload)
        val msg = ChatMessage(p.id, id, nick, dst, text, p.timestamp, 0, outgoing = true)
        executor.execute {
            markSeen(p.id)
            remember(p)
            forward(p, except = null)
            listeners.forEach { it.onMessage(msg) }
        }
        return msg
    }

    // ---- TransportListener (called on transport threads; hop onto the executor) ----

    override fun onNeighborUp(transport: Transport, link: String) = executor.execute {
        val n = Neighbor(transport, link)
        if (!neighbors.add(n)) return@execute
        pruneCarry()
        // Delay-tolerant delivery: hand everything we are carrying to the new neighbor.
        for (c in carry) sendTo(n, c.packet.encode())
        publishNeighbors()
    }

    override fun onNeighborDown(transport: Transport, link: String) = executor.execute {
        if (neighbors.remove(Neighbor(transport, link))) publishNeighbors()
    }

    override fun onFrame(transport: Transport, link: String, frame: ByteArray) = executor.execute {
        val bytes = reassemblers.getOrPut(transport) { Reassembler(clock) }.accept(link, frame)
            ?: return@execute
        val packet = Packet.decode(bytes) ?: return@execute
        handle(packet, Neighbor(transport, link))
    }

    // ---- routing ----

    private fun handle(p: Packet, from: Neighbor) {
        if (!markSeen(p.id) || p.src == id) return

        val chat = if (p.type == Packet.TYPE_CHAT) ChatPayload.decode(p.payload) else null
        if (chat != null) peerNames[p.src] = chat.nick

        if (chat != null && (p.dst == id || p.dst.isBroadcast)) {
            val msg = ChatMessage(p.id, p.src, chat.nick, p.dst, chat.text, p.timestamp, p.hops, outgoing = false)
            listeners.forEach { it.onMessage(msg) }
        }

        if (p.dst != id && p.ttl > 1) {
            val fwd = p.relayed()
            remember(fwd)
            forward(fwd, except = from)
        }
    }

    /** @return true if this id was new. */
    private fun markSeen(pid: PacketId): Boolean = seen.put(pid, true) == null

    private fun remember(p: Packet) {
        carry.addLast(Carried(p, clock()))
        pruneCarry()
    }

    private fun pruneCarry() {
        val cutoff = clock() - config.carryMaxAgeMs
        while (carry.isNotEmpty() && (carry.size > config.carryMax || carry.first().storedAt < cutoff)) {
            carry.removeFirst()
        }
    }

    private fun forward(p: Packet, except: Neighbor?) {
        val bytes = p.encode()
        for (n in neighbors.toList()) if (n != except) sendTo(n, bytes)
    }

    private fun sendTo(n: Neighbor, bytes: ByteArray) {
        val frames = Fragmenter.split(bytes, n.transport.maxFrameSize(n.link), frameCounter++)
        for (f in frames) {
            if (!n.transport.send(n.link, f)) {
                neighbors.remove(n)
                publishNeighbors()
                return
            }
        }
    }

    private fun publishNeighbors() {
        val snap = neighbors.map { it.toString() }
        neighborSnapshot = snap
        listeners.forEach { it.onNeighborsChanged(snap) }
    }
}
