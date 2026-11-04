package com.offgrid.mesh

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Larger, randomized and adversarial scenarios. */
class MeshSimulationTest {
    private val exec = QueueExecutor()

    private fun link(a: TestNode, b: TestNode) { FakeTransport.link(a.radio, b.radio); exec.drain() }

    /** Random geometric graph: nodes on a 1x1 square, linked when within [radius]. */
    private fun village(n: Int, radius: Double, seed: Int, config: MeshNode.Config, dropRate: Double = 0.0):
        Pair<List<TestNode>, Map<Int, Set<Int>>> {
        val rng = Random(seed)
        val nodes = (0 until n).map { i ->
            TestNode("n$i", exec, config, radio = FakeTransport("n$i", mtu = 40, dropRate = dropRate, rng = Random(seed * 1000 + i)))
        }
        exec.drain()
        val pos = List(n) { rng.nextDouble() to rng.nextDouble() }
        val adj = (0 until n).associateWith { mutableSetOf<Int>() }
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = pos[i].first - pos[j].first; val dy = pos[i].second - pos[j].second
            if (dx * dx + dy * dy <= radius * radius) {
                link(nodes[i], nodes[j]); adj.getValue(i) += j; adj.getValue(j) += i
            }
        }
        return nodes to adj
    }

    private fun reachable(adj: Map<Int, Set<Int>>, from: Int): Set<Int> {
        val seen = mutableSetOf(from); val q = ArrayDeque(listOf(from))
        while (q.isNotEmpty()) for (m in adj.getValue(q.removeFirst())) if (seen.add(m)) q += m
        return seen
    }

    @Test fun `random villages - every reachable node gets each message exactly once`() {
        for (seed in 1..8) {
            val (nodes, adj) = village(40, 0.22, seed, MeshNode.Config(defaultTtl = 64))
            val sender = seed % nodes.size
            nodes[sender].node.sendBroadcast("seed $seed"); exec.drain()
            val expected = reachable(adj, sender) - sender
            for ((i, t) in nodes.withIndex()) {
                if (i == sender) continue
                val want = if (i in expected) 1 else 0
                assertEquals(want, t.inbox.size, "seed=$seed node=$i (reachable=${i in expected})")
            }
        }
    }

    @Test fun `dense mesh tolerates 10 percent frame loss`() {
        var delivered = 0; var possible = 0
        for (seed in 1..5) {
            val (nodes, adj) = village(30, 0.35, seed, MeshNode.Config(defaultTtl = 64), dropRate = 0.10)
            repeat(5) { k ->
                val s = (seed + k * 7) % nodes.size
                nodes[s].node.sendBroadcast("m$k"); exec.drain()
                possible += (reachable(adj, s) - s).size
            }
            delivered += nodes.sumOf { it.inbox.size }
        }
        val ratio = delivered.toDouble() / possible
        assertTrue(ratio > 0.95, "delivery ratio $ratio")
    }

    @Test fun `long message crosses a chain over tiny MTU links intact`() {
        val nodes = (0 until 4).map { TestNode("c$it", exec, radio = FakeTransport("c$it", mtu = 20)) }
        exec.drain()
        nodes.zipWithNext().forEach { (a, b) -> link(a, b) }
        val text = buildString { repeat(1300) { append("ñ") } } // 2600 UTF-8 bytes
        nodes[0].node.sendBroadcast(text); exec.drain()
        assertEquals(text, nodes[3].inbox.single().text)
    }

    @Test fun `oversized message is rejected before it hits the air`() {
        val a = TestNode("a", exec); exec.drain()
        assertFailsWith<IllegalArgumentException> { a.node.sendBroadcast("x".repeat(Packet.MAX_PAYLOAD)) }
    }

    @Test fun `carry buffer respects size limit`() {
        val cfg = MeshNode.Config(carryMax = 3)
        val (a, mule, c) = listOf("a", "mule", "c").map { TestNode(it, exec, cfg) }; exec.drain()
        link(a, mule)
        repeat(5) { a.node.sendBroadcast("msg$it") }; exec.drain()
        FakeTransport.unlink(a.radio, mule.radio); exec.drain()
        link(mule, c)
        assertEquals(listOf("msg2", "msg3", "msg4"), c.inbox.map { it.text })
    }

    @Test fun `carry buffer expires old messages`() {
        var now = 1_000_000L
        val cfg = MeshNode.Config(carryMaxAgeMs = 60_000)
        val (a, mule, c) = listOf("a", "mule", "c").map { TestNode(it, exec, cfg, clock = { now }) }; exec.drain()
        link(a, mule)
        a.node.sendBroadcast("stale"); exec.drain()
        FakeTransport.unlink(a.radio, mule.radio); exec.drain()
        now += 61_000
        link(mule, c)
        assertTrue(c.inbox.isEmpty())
    }

    @Test fun `own messages echoed back are not delivered to self`() {
        val (a, b, c) = listOf("a", "b", "c").map { TestNode(it, exec) }; exec.drain()
        link(a, b); link(b, c); link(c, a)
        a.node.sendBroadcast("loop"); exec.drain()
        assertTrue(a.inbox.isEmpty())
        assertEquals(1, b.inbox.size); assertEquals(1, c.inbox.size)
    }

    @Test fun `failed send drops the neighbor`() {
        val (a, b) = listOf("a", "b").map { TestNode(it, exec) }; exec.drain()
        link(a, b)
        assertEquals(1, a.node.neighborSnapshot.size)
        FakeTransport.silentlyUnlink(a.radio, b.radio)
        a.node.sendBroadcast("anyone?"); exec.drain()
        assertTrue(a.node.neighborSnapshot.isEmpty())
    }

    @Test fun `nickname is truncated and peers are learned`() {
        val (a, b) = listOf("a", "b").map { TestNode(it, exec) }; exec.drain()
        a.node.nickname = "A".repeat(100)
        link(a, b)
        a.node.sendBroadcast("hi"); exec.drain()
        assertEquals(ChatPayload.MAX_NICK_CHARS, b.inbox.single().nick.length)
        assertEquals(b.inbox.single().nick, b.node.peers[a.node.id])
    }

    @Test fun `stopped node stops relaying`() {
        val (a, b, c) = listOf("a", "b", "c").map { TestNode(it, exec) }; exec.drain()
        link(a, b); link(b, c)
        b.node.stop(); exec.drain()
        a.node.sendBroadcast("after stop"); exec.drain()
        assertTrue(c.inbox.isEmpty())
    }

    @Test fun `garbage from the air never crashes a node`() {
        val rng = Random(7)
        val a = TestNode("a", exec); exec.drain()
        val evil = FakeTransport("evil")
        repeat(20_000) {
            val len = rng.nextInt(0, 120)
            val frame = rng.nextBytes(len)
            if (len >= 8 && rng.nextBoolean()) { // plausible fragment headers
                frame[4] = 0; frame[5] = rng.nextInt(0, 3).toByte(); frame[6] = 0; frame[7] = rng.nextInt(0, 4).toByte()
            }
            a.node.onFrame(evil, "evil", frame)
        }
        exec.drain()
        repeat(5_000) { Packet.decode(rng.nextBytes(rng.nextInt(0, 200))) }
        repeat(5_000) { ChatPayload.decode(rng.nextBytes(rng.nextInt(0, 50))) }
        assertTrue(a.inbox.isEmpty())
    }

    @Test fun `truncated and corrupted packets are rejected`() {
        val p = Packet(Packet.TYPE_CHAT, 3, 0, PacketId.random(), NodeId.random(), NodeId.BROADCAST, 1L,
            ChatPayload("x", "hello").encode()).encode()
        for (cut in 0 until p.size) assertEquals(null, Packet.decode(p.copyOf(cut)), "cut=$cut")
        assertEquals(null, Packet.decode(p + byteArrayOf(0)))
        assertEquals(null, Packet.decode(p.copyOf().also { it[0] = 9 }))
    }

    @Test fun `duplicate and stray fragments are harmless`() {
        val data = Random(3).nextBytes(300)
        val frames = Fragmenter.split(data, 50, 99)
        val r = Reassembler({ 0L })
        assertEquals(null, r.accept("p", frames[0]))
        assertEquals(null, r.accept("p", frames[0])) // duplicate
        assertEquals(null, r.accept("other", frames[1])) // same id, different link: separate buffer
        var out: ByteArray? = null
        for (f in frames.drop(1)) out = r.accept("p", f) ?: out
        assertContentEquals(data, out)
    }

    @Test fun `node ids round-trip through hex`() {
        repeat(1000) { val id = NodeId.random(); assertEquals(id, NodeId.parse(id.hex())) }
        assertTrue(NodeId.BROADCAST.isBroadcast)
    }
}
