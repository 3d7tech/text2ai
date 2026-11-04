package com.offgrid.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshNodeTest {
    private val exec = QueueExecutor()
    private fun nodes(vararg names: String, config: MeshNode.Config = MeshNode.Config()) =
        names.map { TestNode(it, exec, config) }.also { exec.drain() }

    private fun link(a: TestNode, b: TestNode) { FakeTransport.link(a.radio, b.radio); exec.drain() }

    @Test fun `packet round-trips`() {
        val p = Packet(Packet.TYPE_CHAT, 5, 2, PacketId.random(), NodeId.random(), NodeId.BROADCAST, 123L,
            ChatPayload("amaka", "Habari! 👋").encode())
        val d = assertNotNull(Packet.decode(p.encode()))
        assertEquals(p.id, d.id); assertEquals(5, d.ttl); assertEquals(2, d.hops); assertEquals(p.src, d.src)
        assertTrue(d.dst.isBroadcast)
        assertEquals(ChatPayload("amaka", "Habari! 👋"), ChatPayload.decode(d.payload))
        assertNull(Packet.decode(byteArrayOf(1, 2, 3)))
    }

    @Test fun `fragments reassemble out of order`() {
        val data = ByteArray(1000) { it.toByte() }
        val frames = Fragmenter.split(data, 20, 42)
        assertTrue(frames.all { it.size <= 20 })
        val r = Reassembler({ 0L })
        val shuffled = frames.shuffled(kotlin.random.Random(1))
        val results = shuffled.map { r.accept("peer", it) }
        assertTrue(results.dropLast(1).all { it == null })
        assertTrue(data.contentEquals(results.last()))
    }

    @Test fun `broadcast is relayed across a chain`() {
        val (a, b, c) = nodes("a", "b", "c")
        link(a, b); link(b, c) // a cannot hear c directly
        a.node.sendBroadcast("hello"); exec.drain()
        assertEquals(listOf("hello"), b.inbox.map { it.text })
        assertEquals(listOf("hello"), c.inbox.map { it.text })
        assertEquals(0, b.inbox.single().hops)
        assertEquals(1, c.inbox.single().hops)
        assertEquals("a", c.inbox.single().nick)
    }

    @Test fun `no duplicate delivery in a fully connected cluster`() {
        val ns = nodes("a", "b", "c", "d")
        for (i in ns.indices) for (j in i + 1 until ns.size) link(ns[i], ns[j])
        ns[0].node.sendBroadcast("once"); exec.drain()
        ns.drop(1).forEach { assertEquals(1, it.inbox.size, "${it.name} got duplicates") }
    }

    @Test fun `ttl bounds propagation`() {
        val ns = nodes("n0", "n1", "n2", "n3", "n4", config = MeshNode.Config(defaultTtl = 2))
        ns.zipWithNext().forEach { (x, y) -> link(x, y) }
        ns[0].node.sendBroadcast("short range"); exec.drain()
        assertEquals(listOf(1, 1, 0, 0), ns.drop(1).map { it.inbox.size })
    }

    @Test fun `direct message is relayed but only delivered to its target`() {
        val (a, b, c) = nodes("a", "b", "c")
        link(a, b); link(b, c)
        a.node.sendDirect(c.node.id, "secret-ish"); exec.drain()
        assertTrue(b.inbox.isEmpty())
        val m = c.inbox.single()
        assertEquals("secret-ish", m.text); assertTrue(m.isDirect)
    }

    @Test fun `store and forward via a data mule`() {
        // Village A has "a". "mule" is in range of a, then later travels to village C.
        val (a, mule, c) = nodes("a", "mule", "c")
        link(a, mule)
        a.node.sendBroadcast("market prices: maize 4500/bag"); exec.drain()
        FakeTransport.unlink(a.radio, mule.radio); exec.drain()

        assertTrue(c.inbox.isEmpty())
        link(mule, c) // the mule arrives hours later
        assertEquals("market prices: maize 4500/bag", c.inbox.single().text)

        // Direct messages travel the same way.
        FakeTransport.unlink(mule.radio, c.radio); exec.drain()
        link(a, mule)
        a.node.sendDirect(c.node.id, "reply when you can"); exec.drain()
        FakeTransport.unlink(a.radio, mule.radio); exec.drain()
        link(mule, c)
        assertEquals("reply when you can", c.inbox.last().text)
        assertTrue(mule.inbox.size == 1, "mule must not see the DM")
    }

    @Test fun `messages survive two radios reaching the same peer`() {
        val (a, b) = nodes("a", "b")
        val extraA = FakeTransport("a-wifi"); val extraB = FakeTransport("b-wifi")
        a.node.start(listOf(extraA)); b.node.start(listOf(extraB)); exec.drain()
        link(a, b); FakeTransport.link(extraA, extraB); exec.drain()
        a.node.sendBroadcast("dual radio"); exec.drain()
        assertEquals(1, b.inbox.size)
    }
}
