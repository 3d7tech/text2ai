package com.offgrid.mesh.hub

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubServerTest {
    private val dir = kotlin.io.path.createTempDirectory("hub").toFile()
    private var store = HubStore(dir, maxMediaBytes = 2_000_000)
    private var hub = HubServer(store, "testkey123")
    private val base get() = "http://127.0.0.1:${hub.port}"

    @AfterTest fun cleanup() { hub.stop(); dir.deleteRecursively() }

    private fun conn(path: String, method: String = "GET", key: Boolean = true): HttpURLConnection {
        val sep = if ('?' in path) "&" else "?"
        val c = URL(base + path + (if (key) "${sep}k=testkey123" else "")).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000; c.readTimeout = 20000
        return c
    }

    private fun send(name: String, text: String, media: String? = null, cid: String = "c1"): Int {
        val c = conn("/api/send", "POST")
        c.doOutput = true
        val body = listOfNotNull("name" to name, "text" to text, "cid" to cid, media?.let { "media" to it })
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        return c.responseCode
    }

    private fun upload(bytes: ByteArray, type: String): HttpURLConnection {
        val c = conn("/api/upload", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", type)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        return c
    }

    private fun idOf(json: String) = Regex("\"id\":\"([0-9a-f]+)\"").find(json)!!.groupValues[1]

    @Test fun `serves the installable web app without a key`() {
        hub.start(0)
        val page = conn("/", key = false)
        assertEquals(200, page.responseCode)
        val html = page.inputStream.readBytes().decodeToString()
        assertTrue("apple-touch-icon" in html && "EventSource" in html)
        for (icon in listOf("/apple-touch-icon.png", "/icon-192.png", "/icon-512.png")) {
            val c = conn(icon, key = false)
            assertEquals(200, c.responseCode, icon)
            val b = c.inputStream.readBytes()
            assertEquals(0x89.toByte(), b[0]); assertEquals('P'.code.toByte(), b[1])
        }
        val manifest = conn("/manifest.webmanifest").inputStream.readBytes().decodeToString()
        assertTrue("\"start_url\":\"/?k=testkey123\"" in manifest)
        val anon = conn("/manifest.webmanifest", key = false).inputStream.readBytes().decodeToString()
        assertTrue("testkey123" !in anon, "key must not leak without the key")
    }

    @Test fun `page carries the key in its manifest link so Add to Home Screen keeps it`() {
        hub.start(0)
        val withKey = conn("/").inputStream.readBytes().decodeToString()
        assertTrue("href=\"/manifest.webmanifest?k=testkey123\"" in withKey)
        val without = conn("/", key = false).inputStream.readBytes().decodeToString()
        assertTrue("href=\"/manifest.webmanifest\"" in without && "testkey123" !in without)
        val wrong = conn("/?k=nope", key = false).inputStream.readBytes().decodeToString()
        assertTrue("testkey123" !in wrong)
    }

    @Test fun `api requires the household key`() {
        hub.start(0)
        assertEquals(401, conn("/api/history", key = false).responseCode)
        assertEquals(401, conn("/api/history?k=wrong", key = false).responseCode)
        assertEquals(401, conn("/media/0123456789abcdef", key = false).responseCode)
        assertEquals(200, conn("/api/history").responseCode)
    }

    @Test fun `messages round-trip through history with unicode and escaping`() {
        hub.start(0)
        assertEquals(200, send("Mum", "Food is ready 🍲\n\"come\" <now>"))
        assertEquals(200, send("Tunde", "Coming!", cid = "c2"))
        val json = conn("/api/history").inputStream.readBytes().decodeToString()
        assertTrue("\"from\":\"Mum\"" in json)
        assertTrue("Food is ready 🍲\\n\\\"come\\\" <now>" in json)
        assertTrue("\"seq\":2" in json && "\"cid\":\"c2\"" in json)
        assertEquals(400, send("Mum", "   "))
    }

    @Test fun `live events stream delivers new messages`() {
        hub.start(0)
        send("Ada", "first")
        val c = conn("/api/events?after=0")
        assertEquals("text/event-stream; charset=utf-8", c.contentType)
        val r = BufferedReader(InputStreamReader(c.inputStream))
        fun nextData(): String { while (true) { val l = r.readLine()!!; if (l.startsWith("data: ")) return l } }
        assertTrue("first" in nextData())
        Thread { Thread.sleep(200); send("Dad", "second") }.start()
        assertTrue("second" in nextData())
        c.disconnect()
    }

    @Test fun `events resume from Last-Event-ID`() {
        hub.start(0)
        repeat(3) { send("A", "m$it") }
        val c = conn("/api/events")
        c.setRequestProperty("Last-Event-ID", "2")
        val r = BufferedReader(InputStreamReader(c.inputStream))
        var line: String
        do { line = r.readLine() } while (!line.startsWith("data: "))
        assertTrue("\"text\":\"m2\"" in line, line)
        c.disconnect()
    }

    @Test fun `photo upload, message and range requests for video playback`() {
        hub.start(0)
        val bytes = Random(9).nextBytes(150_000)
        val up = upload(bytes, "video/mp4")
        assertEquals(200, up.responseCode)
        val id = idOf(up.inputStream.readBytes().decodeToString())
        assertEquals(200, send("Ada", "", media = id))
        assertTrue("\"mediaType\":\"video/mp4\"" in conn("/api/history").inputStream.readBytes().decodeToString())

        val full = conn("/media/$id")
        assertEquals(200, full.responseCode); assertEquals("bytes", full.getHeaderField("Accept-Ranges"))
        assertContentEquals(bytes, full.inputStream.readBytes())

        val part = conn("/media/$id").apply { setRequestProperty("Range", "bytes=0-1") }
        assertEquals(206, part.responseCode)
        assertEquals("bytes 0-1/150000", part.getHeaderField("Content-Range"))
        assertContentEquals(bytes.copyOfRange(0, 2), part.inputStream.readBytes())

        val tail = conn("/media/$id").apply { setRequestProperty("Range", "bytes=149990-") }
        assertContentEquals(bytes.copyOfRange(149990, 150000), tail.inputStream.readBytes())

        val suffix = conn("/media/$id").apply { setRequestProperty("Range", "bytes=-5") }
        assertContentEquals(bytes.copyOfRange(149995, 150000), suffix.inputStream.readBytes())

        assertEquals(416, conn("/media/$id").apply { setRequestProperty("Range", "bytes=999999-") }.responseCode)
    }

    @Test fun `upload validation`() {
        hub.start(0)
        assertEquals(415, upload(byteArrayOf(1, 2, 3), "application/x-msdownload").responseCode)
        assertEquals(413, upload(ByteArray(2_000_001), "image/jpeg").responseCode)
        assertEquals(400, send("A", "", media = "ffffffffffffffff"))
        assertEquals(404, conn("/media/../messages.tsv").responseCode)
        assertEquals(404, conn("/media/ABCDEF").responseCode)
    }

    @Test fun `history and media survive a hub restart`() {
        hub.start(0)
        send("Mum", "remember me")
        val id = idOf(upload(byteArrayOf(9, 8, 7), "image/png").inputStream.readBytes().decodeToString())
        send("Mum", "pic", media = id)
        hub.stop()

        store = HubStore(dir); hub = HubServer(store, "testkey123"); hub.start(0)
        val json = conn("/api/history").inputStream.readBytes().decodeToString()
        assertTrue("remember me" in json && id in json)
        assertContentEquals(byteArrayOf(9, 8, 7), conn("/media/$id").inputStream.readBytes())
        assertEquals("image/png", conn("/media/$id").contentType)
    }

    @Test fun `web messages reach the bridge callback and mesh messages reach browsers`() {
        hub.start(0)
        var bridged: HubMessage? = null
        hub.onWebMessage = { bridged = it }
        send("Ada", "hi mesh")
        assertEquals("hi mesh", bridged?.text)
        store.add("Phone-Tunde", "hi web", null, null, "mesh", "mesh:abc")
        val json = conn("/api/history").inputStream.readBytes().decodeToString()
        assertTrue("\"origin\":\"mesh\"" in json && "hi web" in json)
    }

    @Test fun `store rejects bad input and bounds text`() {
        assertFailsWith<IllegalArgumentException> { store.add("x", "", null, null, "web", "c") }
        val m = store.add("y".repeat(100), "z".repeat(10_000), null, null, "web", "c")
        assertEquals(HubStore.MAX_NAME, m.from.length); assertEquals(HubStore.MAX_TEXT, m.text.length)
        assertNull(store.media("../../etc"))
        assertNotNull(HubServer.newKey().takeIf { it.length == 10 })
    }

    @Test fun `invite QR code is drawn by the hub and needs the key`() {
        hub.start(0)
        assertEquals(401, conn("/api/qr.svg?text=x", key = false).responseCode)
        assertEquals(400, conn("/api/qr.svg").responseCode)
        val c = conn("/api/qr.svg?text=" + URLEncoder.encode("http://192.168.1.20:8090/?k=testkey123", "UTF-8"))
        assertEquals(200, c.responseCode)
        assertEquals("image/svg+xml", c.contentType)
        val svg = c.inputStream.readBytes().decodeToString()
        assertTrue(svg.startsWith("<svg") && svg.contains("h1v1h-1z"))
    }

    @Test fun `connections are reused between requests`() {
        hub.start(0)
        java.net.Socket("127.0.0.1", hub.port).use { sock ->
            sock.soTimeout = 5000
            val out = sock.getOutputStream()
            val inp = java.io.BufferedInputStream(sock.getInputStream())
            fun readResponse(): Pair<String, String> {
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(inp.read().toChar())
                val len = Regex("Content-Length: (\\d+)").find(head)!!.groupValues[1].toInt()
                return head.toString() to String(inp.readNBytesCompat(len))
            }
            val form = "name=A&cid=c&text=hello"
            out.write(("POST /api/send?k=testkey123 HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: ${form.length}\r\n\r\n$form").toByteArray())
            val (h1, b1) = readResponse()
            assertTrue("keep-alive" in h1, h1); assertTrue("\"seq\":1" in b1)
            out.write("GET /api/history?k=testkey123 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val (_, b2) = readResponse()
            assertTrue("hello" in b2)
            // an unauthorised POST whose body is never read must not corrupt the next request
            out.write("POST /api/send HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nabcde".toByteArray())
            val (h3, _) = readResponse()
            assertTrue(h3.startsWith("HTTP/1.1 401"), h3)
            out.write("GET /icon-192.png HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
            val (h4, _) = readResponse()
            assertTrue(h4.startsWith("HTTP/1.1 200") && "Connection: close" in h4, h4)
        }
    }

    @Test fun `photos carry a thumbnail and old logs still load`() {
        hub.start(0)
        val full = idOf(upload(Random(1).nextBytes(5000), "image/jpeg").inputStream.readBytes().decodeToString())
        val thumb = idOf(upload(Random(2).nextBytes(500), "image/jpeg").inputStream.readBytes().decodeToString())
        val c = conn("/api/send", "POST"); c.doOutput = true
        c.outputStream.use { it.write("name=A&cid=c&text=&media=$full&thumb=$thumb".toByteArray()) }
        assertEquals(200, c.responseCode)
        assertTrue("\"thumb\":\"$thumb\"" in conn("/api/history").inputStream.readBytes().decodeToString())
        val bad = conn("/api/send", "POST"); bad.doOutput = true
        bad.outputStream.use { it.write("name=A&cid=c&text=x&media=$full&thumb=ffffffffffffffff".toByteArray()) }
        assertEquals(400, bad.responseCode)
        hub.stop()
        // a log line written before thumbnails existed (8 fields)
        File(dir, "messages.tsv").appendText("99\t1\tOld\thi\t-\t-\tweb\tc\n")
        store = HubStore(dir); hub = HubServer(store, "testkey123"); hub.start(0)
        val json = conn("/api/history").inputStream.readBytes().decodeToString()
        assertTrue("\"from\":\"Old\"" in json && "\"thumb\":null" in json, json)
    }

    @Test fun `event stream sends a heartbeat the app can see`() {
        hub.stop()
        hub = HubServer(store, "testkey123", heartbeatMs = 200)
        hub.start(0)
        val c = conn("/api/events?after=0")
        val r = BufferedReader(InputStreamReader(c.inputStream))
        val start = System.currentTimeMillis()
        var line: String
        do { line = r.readLine() } while (line != "event: ping")
        assertTrue(System.currentTimeMillis() - start < 3000)
        assertEquals("data: {}", r.readLine())
        c.disconnect()
    }

    @Test fun `range parsing edge cases`() {
        assertNull(parseRange(null, 10)); assertNull(parseRange("bytes=0-1,3-4", 10)); assertNull(parseRange("items=0-1", 10))
        assertEquals(0L to 9L, parseRange("bytes=0-", 10))
        assertEquals(5L to 9L, parseRange("bytes=5-100", 10))
        assertEquals(INVALID_RANGE, parseRange("bytes=10-", 10))
        assertEquals(INVALID_RANGE, parseRange("bytes=5-2", 10))
        assertEquals(0L to 9L, parseRange("bytes=-50", 10))
    }

    @Test fun `concurrent senders get unique sequence numbers`() {
        hub.start(0)
        val threads = (1..8).map { t -> Thread { repeat(10) { send("u$t", "m$it", cid = "c$t") } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        val seqs = store.after(0).map { it.seq }
        assertEquals(80, seqs.size); assertEquals(seqs.sorted(), seqs); assertEquals(80, seqs.toSet().size)
    }
}
