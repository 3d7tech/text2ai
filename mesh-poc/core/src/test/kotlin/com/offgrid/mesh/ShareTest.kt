package com.offgrid.mesh

import com.offgrid.mesh.share.ApkPackager
import com.offgrid.mesh.share.ApkWebServer
import com.offgrid.mesh.share.LocalAddresses
import com.offgrid.mesh.share.WifiQr
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareTest {
    private val dir = kotlin.io.path.createTempDirectory("share").toFile()
    private val apkBytes = Random(5).nextBytes(300_000)
    private val apk = File(dir, "base.apk").apply { writeBytes(apkBytes) }
    private val server = ApkWebServer(apk)

    @AfterTest fun cleanup() { server.stop(); dir.deleteRecursively() }

    private fun get(path: String, method: String = "GET"): HttpURLConnection {
        val c = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        return c
    }

    @Test fun `serves the apk byte for byte with install-friendly headers`() {
        server.start(0)
        val c = get("/OffgridMesh.apk")
        assertEquals(200, c.responseCode)
        assertEquals(ApkWebServer.APK_MIME, c.contentType)
        assertEquals(apkBytes.size.toLong(), c.contentLengthLong)
        assertTrue(c.getHeaderField("Content-Disposition").contains("OffgridMesh.apk"))
        assertContentEquals(apkBytes, c.inputStream.readBytes())
        // download counter updates after the body is flushed
        val deadline = System.currentTimeMillis() + 2000
        while (server.downloads == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, server.downloads)
    }

    @Test fun `landing page links to the apk`() {
        server.start(0)
        val c = get("/")
        assertEquals(200, c.responseCode)
        assertTrue(c.contentType.startsWith("text/html"))
        val html = c.inputStream.readBytes().decodeToString()
        assertTrue(html.contains("href=\"/OffgridMesh.apk\""))
        assertTrue(html.contains("0.3 MB"))
    }

    @Test fun `head, unknown paths, bad methods and junk requests`() {
        server.start(0)
        assertEquals(200, get("/OffgridMesh.apk", "HEAD").responseCode)
        assertEquals(0, server.downloads)
        assertEquals(404, get("/etc/passwd").responseCode)
        assertEquals(404, get("/../base.apk").responseCode)
        assertEquals(405, get("/", "POST").also { it.doOutput = true; it.outputStream.write(1) }.responseCode)
        Socket("127.0.0.1", server.port).use { it.getOutputStream().write(Random(1).nextBytes(5000)) }
        Socket("127.0.0.1", server.port).use { } // connect and hang up
        assertEquals(200, get("/").responseCode) // still alive
    }

    @Test fun `parallel downloads`() {
        server.start(0)
        val threads = (1..5).map { Thread { assertContentEquals(apkBytes, get("/OffgridMesh.apk").inputStream.readBytes()) } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
    }

    @Test fun `falls back to a free port when the preferred one is taken`() {
        java.net.ServerSocket(0).use { busy ->
            val port = server.start(busy.localPort)
            assertTrue(port > 0 && port != busy.localPort)
            assertEquals(200, get("/").responseCode)
        }
    }

    @Test fun `zip contains the apk`() {
        val z = ApkPackager.zip(apk, File(dir, "out/OffgridMesh-apk.zip"), "OffgridMesh.apk")
        ZipFile(z).use { zf ->
            val e = zf.getEntry("OffgridMesh.apk")
            assertContentEquals(apkBytes, zf.getInputStream(e).readBytes())
        }
    }

    @Test fun `wifi qr payload escapes special characters`() {
        assertEquals("WIFI:T:WPA;S:Mesh;P:pass1234;;", WifiQr.payload("Mesh", "pass1234"))
        assertEquals("""WIFI:T:WPA;S:a\;b\,c;P:x\:y\\z\";;""", WifiQr.payload("a;b,c", """x:y\z""""))
        assertEquals("WIFI:T:nopass;S:Open;;", WifiQr.payload("Open", null))
    }

    @Test fun `local address lookup never throws`() {
        val addrs = LocalAddresses.siteLocalIpv4()
        assertTrue(addrs.all { it.ip.count { c -> c == '.' } == 3 })
    }
}
