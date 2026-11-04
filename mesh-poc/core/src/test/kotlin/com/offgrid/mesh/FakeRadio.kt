package com.offgrid.mesh

import java.util.concurrent.Executor

/** Deterministic executor: tasks run only when [drain] is called. */
class QueueExecutor : Executor {
    private val q = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { q.addLast(command) }
    fun drain() {
        var n = 0
        while (q.isNotEmpty()) {
            q.removeFirst().run()
            check(++n < 1_000_000) { "runaway" }
        }
    }
}

/** In-memory radio. [link] / [unlink] model two devices coming in and out of range. */
class FakeTransport(
    val label: String,
    private val mtu: Int = 40,
    private val dropRate: Double = 0.0,
    private val rng: kotlin.random.Random = kotlin.random.Random(0),
) : Transport {
    override val name = "fake"
    private lateinit var listener: TransportListener
    private val peers = mutableMapOf<String, FakeTransport>()
    var framesSent = 0

    override fun start(listener: TransportListener) { this.listener = listener }
    override fun stop() {}
    override fun maxFrameSize(link: String) = mtu

    override fun send(link: String, frame: ByteArray): Boolean {
        val p = peers[link] ?: return false
        framesSent++
        if (dropRate > 0 && rng.nextDouble() < dropRate) return true // lost in the air
        p.listener.onFrame(p, label, frame)
        return true
    }

    companion object {
        fun link(a: FakeTransport, b: FakeTransport) {
            a.peers[b.label] = b
            b.peers[a.label] = a
            a.listener.onNeighborUp(a, b.label)
            b.listener.onNeighborUp(b, a.label)
        }

        /** Radio contact lost without either side being told (e.g. walked out of range). */
        fun silentlyUnlink(a: FakeTransport, b: FakeTransport) {
            a.peers.remove(b.label)
            b.peers.remove(a.label)
        }

        fun unlink(a: FakeTransport, b: FakeTransport) {
            a.peers.remove(b.label)
            b.peers.remove(a.label)
            a.listener.onNeighborDown(a, b.label)
            b.listener.onNeighborDown(b, a.label)
        }
    }
}

class TestNode(
    val name: String,
    exec: QueueExecutor,
    config: MeshNode.Config = MeshNode.Config(),
    clock: () -> Long = System::currentTimeMillis,
    val radio: FakeTransport = FakeTransport(name),
) {
    val node = MeshNode(NodeId.random(), name, exec, clock, config)
    val inbox = mutableListOf<ChatMessage>()

    init {
        node.addListener(object : MeshNode.Listener {
            override fun onMessage(message: ChatMessage) { if (!message.outgoing) inbox += message }
        })
        node.start(listOf(radio))
    }
}
