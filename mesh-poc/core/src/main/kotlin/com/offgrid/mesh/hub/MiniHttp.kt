package com.offgrid.mesh.hub

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Parsed HTTP/1.1 request. Header names are lower-cased. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
) {
    fun header(name: String): String? = headers[name.lowercase()]
    val contentLength: Long get() = header("content-length")?.toLongOrNull() ?: -1

    /** Reads an `application/x-www-form-urlencoded` body (capped at [max] bytes). */
    fun form(max: Int = 64 * 1024): Map<String, String> {
        val n = contentLength
        if (n < 0 || n > max) throw HttpError(413, "Body too large")
        val bytes = body.readNBytesCompat(n.toInt())
        return parseUrlEncoded(String(bytes, StandardCharsets.UTF_8))
    }
}

class HttpError(val code: Int, message: String) : Exception(message)

/** Writes one response. Every response closes the connection (simple, and fine on a LAN). */
class HttpResponse(private val out: OutputStream) {
    var committed = false
        private set

    fun send(code: Int, type: String, body: ByteArray, headers: Map<String, String> = emptyMap(), head: Boolean = false) {
        val o = start(code, type, body.size.toLong(), headers)
        if (!head) o.write(body)
        o.flush()
    }

    fun text(code: Int, msg: String) = send(code, "text/plain; charset=utf-8", msg.toByteArray(StandardCharsets.UTF_8))

    fun json(code: Int, json: String) = send(code, "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8))

    /** Writes the status line + headers and returns the body stream. [length] null = until close. */
    fun start(code: Int, type: String, length: Long?, headers: Map<String, String> = emptyMap()): OutputStream {
        check(!committed) { "response already started" }
        committed = true
        val sb = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
            .append("Content-Type: $type\r\n")
            .append("Connection: close\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
        if (length != null) sb.append("Content-Length: $length\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
        return out
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 206 -> "Partial Content"; 304 -> "Not Modified"; 400 -> "Bad Request"
        401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 411 -> "Length Required"
        413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; 416 -> "Range Not Satisfiable"
        507 -> "Insufficient Storage"; else -> if (code >= 500) "Server Error" else "Status"
    }
}

/** Minimal thread-per-connection HTTP server for LAN use. Subclasses implement [handle]. */
abstract class MiniHttpServer(private val threadName: String) {
    @Volatile private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    val port: Int get() = server?.localPort ?: -1
    val isRunning: Boolean get() = server?.isClosed == false

    /** Binds [preferredPort], falling back to any free port. Returns the bound port. */
    @Synchronized
    fun start(preferredPort: Int): Int {
        if (isRunning) return port
        val s = ServerSocket()
        s.reuseAddress = true
        try { s.bind(InetSocketAddress(preferredPort)) } catch (e: IOException) { s.bind(InetSocketAddress(0)) }
        val p = Executors.newCachedThreadPool { r -> Thread(r, threadName).apply { isDaemon = true } }
        server = s
        pool = p
        p.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { break }
                p.execute { serve(c) }
            }
        }
        return s.localPort
    }

    @Synchronized
    open fun stop() {
        runCatching { server?.close() }
        pool?.shutdownNow()
        server = null
        pool = null
    }

    protected abstract fun handle(req: HttpRequest, res: HttpResponse)

    private fun serve(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 30_000
                sock.tcpNoDelay = true
                val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
                val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
                val res = HttpResponse(out)
                val req = parse(input)
                if (req == null) {
                    res.text(400, "Bad request")
                } else {
                    try {
                        handle(req, res)
                    } catch (e: HttpError) {
                        if (!res.committed) res.text(e.code, e.message ?: "Error")
                    } catch (e: IOException) {
                        throw e
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (e: Exception) {
                        if (!res.committed) res.text(500, "Internal error")
                    }
                }
                out.flush()
            } catch (_: IOException) {
                // client went away
            }
        }
    }

    private fun parse(input: InputStream): HttpRequest? {
        val line = readLine(input) ?: return null
        val parts = line.split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        repeat(100) {
            val h = readLine(input) ?: return null
            if (h.isEmpty()) {
                val target = parts[1]
                val path = target.substringBefore('?')
                val q = if ('?' in target) parseUrlEncoded(target.substringAfter('?')) else emptyMap()
                val decodedPath = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
                return HttpRequest(parts[0].uppercase(), decodedPath, q, headers, input)
            }
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        return null
    }

    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\n'.code) return buf.toString().trimEnd('\r')
            if (buf.length >= 16 * 1024) return null
            buf.append(b.toChar())
        }
    }
}

fun parseUrlEncoded(s: String): Map<String, String> =
    s.split('&').filter { it.isNotEmpty() }.associate { kv ->
        val k = kv.substringBefore('=')
        val v = if ('=' in kv) kv.substringAfter('=') else ""
        fun dec(x: String) = runCatching { URLDecoder.decode(x, "UTF-8") }.getOrDefault(x)
        dec(k) to dec(v)
    }

/** Reads exactly [n] bytes or throws (InputStream.readNBytes needs API 33 on Android). */
fun InputStream.readNBytesCompat(n: Int): ByteArray {
    val out = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(out, off, n - off)
        if (r < 0) throw IOException("unexpected end of body")
        off += r
    }
    return out
}

fun jsonString(s: String?): String {
    if (s == null) return "null"
    val sb = StringBuilder("\"")
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    return sb.append('"').toString()
}
