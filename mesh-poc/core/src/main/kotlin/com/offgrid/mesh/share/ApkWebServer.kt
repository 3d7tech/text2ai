package com.offgrid.mesh.share

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tiny HTTP server that hands the app's own APK to any phone on the same local network
 * (typically this phone's hotspot). The receiving phone needs nothing but a browser.
 * No internet is involved: traffic never leaves the local Wi-Fi link.
 */
class ApkWebServer(
    private val apk: File,
    val fileName: String = "OffgridMesh.apk",
    private val appName: String = "Offgrid Mesh",
) {
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "apk-web").apply { isDaemon = true } }
    private val downloadCount = AtomicInteger()

    /** Completed APK downloads served so far. */
    val downloads: Int get() = downloadCount.get()

    val port: Int get() = server?.localPort ?: -1

    /** Starts on [preferredPort], or any free port if it is taken. Returns the bound port. */
    fun start(preferredPort: Int = 8080): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        try {
            s.bind(InetSocketAddress(preferredPort))
        } catch (e: IOException) {
            s.bind(InetSocketAddress(0))
        }
        server = s
        pool.execute { acceptLoop(s) }
        return s.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try { s.accept() } catch (e: IOException) { break }
            pool.execute { runCatching { handle(client) } }
        }
    }

    private fun handle(socket: Socket) = socket.use { sock ->
        sock.soTimeout = 15_000
        val input = BufferedInputStream(sock.getInputStream())
        val requestLine = readLine(input) ?: return@use
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
        }
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: ""
        val path = (parts.getOrNull(1) ?: "/").substringBefore('?')
        val head = method == "HEAD"
        val out = BufferedOutputStream(sock.getOutputStream())
        when {
            method != "GET" && !head -> respond(out, 405, "Method Not Allowed", "text/plain", "Method not allowed".bytes(), head)
            path == "/" || path == "/index.html" -> respond(out, 200, "OK", "text/html; charset=utf-8", page().bytes(), head)
            path == "/$fileName" -> sendApk(out, head)
            else -> respond(out, 404, "Not Found", "text/plain", "Not found".bytes(), head)
        }
        out.flush()
    }

    private fun sendApk(out: OutputStream, head: Boolean) {
        writeHeaders(
            out, 200, "OK", APK_MIME, apk.length(),
            "Content-Disposition: attachment; filename=\"$fileName\"",
        )
        if (head) return
        apk.inputStream().use { it.copyTo(out, 64 * 1024) }
        out.flush()
        downloadCount.incrementAndGet()
    }

    private fun respond(out: OutputStream, code: Int, reason: String, type: String, body: ByteArray, head: Boolean) {
        writeHeaders(out, code, reason, type, body.size.toLong())
        if (!head) out.write(body)
    }

    private fun writeHeaders(out: OutputStream, code: Int, reason: String, type: String, length: Long, vararg extra: String) {
        val sb = StringBuilder()
            .append("HTTP/1.1 $code $reason\r\n")
            .append("Content-Type: $type\r\n")
            .append("Content-Length: $length\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
        extra.forEach { sb.append(it).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\n'.code) return buf.toString().trimEnd('\r')
            if (buf.length < 8192) buf.append(b.toChar())
        }
    }

    private fun String.bytes() = toByteArray(StandardCharsets.UTF_8)

    private fun page(): String {
        val mb = "%.1f".format(apk.length() / 1_048_576.0)
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Get $appName</title>
<style>
body{margin:0;font:17px/1.5 system-ui,sans-serif;background:#f7f5f0;color:#1d1b18}
main{max-width:520px;margin:0 auto;padding:28px 16px}
h1{font-size:1.6rem;margin:0 0 6px}p{color:#5c564d}
a.b{display:block;text-align:center;background:#0f7a55;color:#fff;text-decoration:none;font-weight:600;padding:16px;border-radius:12px;margin:20px 0 8px}
ol{padding-left:1.2rem}li{margin:6px 0}
@media (prefers-color-scheme:dark){body{background:#14130f;color:#eeeae2}p{color:#a39d92}a.b{background:#3fbf8c;color:#06140e}}
</style></head><body><main>
<h1>$appName</h1>
<p>Chat with nearby phones with no internet and no mobile network.</p>
<a class="b" href="/$fileName" download>Download app ($mb MB)</a>
<ol>
<li>Tap <b>Download</b>, then open the file.</li>
<li>If asked, allow your browser to <b>install unknown apps</b>.</li>
<li>Open $appName and allow <b>Nearby devices</b>.</li>
<li>Reconnect to your usual Wi-Fi afterwards if you like.</li>
</ol>
</main></body></html>"""
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
