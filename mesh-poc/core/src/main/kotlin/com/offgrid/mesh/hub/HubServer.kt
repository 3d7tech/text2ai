package com.offgrid.mesh.hub

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Household hub: serves the web chat app to iPhones, Chromebooks, tablets, anything with a
 * browser on the same Wi-Fi (no internet needed), and relays messages, photos and videos
 * between them. Runs inside the Android app on an always-plugged-in device.
 *
 * Routes (all `/api` and `/media` routes need the household key `k`):
 * ```
 * GET  /                         web app (installable: Add to Home Screen)
 * GET  /manifest.webmanifest     PWA manifest (start_url carries the key)
 * GET  /api/events?after=N       Server-Sent Events stream of messages
 * GET  /api/history?after=N      JSON array of messages
 * POST /api/send                 form: name, cid, text, media, mediaType
 * POST /api/upload               raw body, Content-Type image/(any) or video/(any)
 * GET  /media/{id}               file, with Range support (needed for iOS video)
 * ```
 */
class HubServer(
    private val store: HubStore,
    val key: String,
    private val appName: String = "Family Chat",
) : MiniHttpServer("hub") {

    companion object {
        const val DEFAULT_PORT = 8090
        private val rng = SecureRandom()

        fun newKey(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            return (1..10).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
        }
    }

    /** Invoked for messages posted from browsers (e.g. to bridge them onto the radio mesh). */
    @Volatile var onWebMessage: ((HubMessage) -> Unit)? = null

    private fun asset(name: String): ByteArray =
        HubServer::class.java.getResourceAsStream("/hub/$name")?.use { it.readBytes() }
            ?: throw HttpError(404, "Not found")

    private fun authorized(req: HttpRequest): Boolean {
        val given = req.query["k"] ?: req.header("x-hub-key") ?: return false
        return MessageDigest.isEqual(given.toByteArray(), key.toByteArray())
    }

    override fun handle(req: HttpRequest, res: HttpResponse) {
        val get = req.method == "GET" || req.method == "HEAD"
        val head = req.method == "HEAD"
        val p = req.path
        when {
            get && (p == "/" || p == "/index.html") ->
                res.send(200, "text/html; charset=utf-8", asset("index.html"), mapOf("Cache-Control" to "no-cache"), head)
            get && (p == "/apple-touch-icon.png" || p == "/icon-192.png" || p == "/icon-512.png") ->
                res.send(200, "image/png", asset(p.removePrefix("/")), mapOf("Cache-Control" to "max-age=86400"), head)
            get && p == "/manifest.webmanifest" -> res.send(200, "application/manifest+json", manifest(req).toByteArray(), head = head)
            p.startsWith("/api/") || p.startsWith("/media/") -> {
                if (!authorized(req)) return res.json(401, """{"error":"household key required"}""")
                when {
                    get && p == "/api/events" -> events(req, res)
                    get && p == "/api/history" -> history(req, res)
                    req.method == "POST" && p == "/api/send" -> send(req, res)
                    req.method == "POST" && p == "/api/upload" -> upload(req, res)
                    get && p.startsWith("/media/") -> media(req, res, p.removePrefix("/media/"), head)
                    else -> res.json(405, """{"error":"method not allowed"}""")
                }
            }
            else -> res.text(404, "Not found")
        }
    }

    private fun manifest(req: HttpRequest): String {
        val start = if (authorized(req)) "/?k=$key" else "/"
        return """{"name":${jsonString(appName)},"short_name":${jsonString(appName)},"start_url":${jsonString(start)},""" +
            """"scope":"/","display":"standalone","background_color":"#f7f5f0","theme_color":"#0f7a55",""" +
            """"icons":[{"src":"/icon-192.png","sizes":"192x192","type":"image/png"},""" +
            """{"src":"/icon-512.png","sizes":"512x512","type":"image/png","purpose":"any maskable"}]}"""
    }

    private fun startSeq(req: HttpRequest): Long {
        val requested = req.header("last-event-id")?.toLongOrNull() ?: req.query["after"]?.toLongOrNull() ?: 0
        // A brand-new client gets the most recent 300 messages, not the whole history.
        return if (requested <= 0) maxOf(0, store.latestSeq - 300) else requested
    }

    private fun history(req: HttpRequest, res: HttpResponse) {
        val list = store.after(startSeq(req), 1000)
        res.json(200, list.joinToString(",", "[", "]") { it.toJson() })
    }

    private fun events(req: HttpRequest, res: HttpResponse) {
        var last = startSeq(req)
        val out = res.start(200, "text/event-stream; charset=utf-8", null, mapOf("Cache-Control" to "no-store"))
        out.write("retry: 2000\n\n".toByteArray())
        out.flush()
        try {
            while (!Thread.currentThread().isInterrupted) {
                val batch = store.awaitAfter(last, 15_000)
                val sb = StringBuilder()
                if (batch.isEmpty()) sb.append(": ping\n\n")
                for (m in batch) {
                    sb.append("id: ").append(m.seq).append("\ndata: ").append(m.toJson()).append("\n\n")
                    last = m.seq
                }
                out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        } catch (_: InterruptedException) {
            // server stopping
        } catch (_: IOException) {
            // client disconnected
        }
    }

    private fun send(req: HttpRequest, res: HttpResponse) {
        val f = req.form()
        val mediaId = f["media"]?.takeIf { it.isNotEmpty() }
        val mediaType = mediaId?.let { store.media(it)?.second }
        if (mediaId != null && mediaType == null) throw HttpError(400, "unknown media")
        val text = f["text"].orEmpty()
        if (text.isBlank() && mediaId == null) throw HttpError(400, "empty message")
        val m = store.add(f["name"].orEmpty(), text, mediaId, mediaType, "web", f["cid"].orEmpty())
        runCatching { onWebMessage?.invoke(m) }
        res.json(200, """{"seq":${m.seq}}""")
    }

    private fun upload(req: HttpRequest, res: HttpResponse) {
        val length = req.contentLength
        if (length < 0) throw HttpError(411, "Content-Length required")
        if (length == 0L) throw HttpError(400, "empty file")
        if (length > store.maxMediaBytes) throw HttpError(413, "File too large (max ${store.maxMediaBytes / 1_048_576} MB)")
        val type = req.header("content-type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (!(type.startsWith("image/") || type.startsWith("video/"))) throw HttpError(415, "Only photos and videos")
        val id = store.saveMedia(req.body, length, type)
        res.json(200, """{"id":"$id","type":${jsonString(type)}}""")
    }

    private fun media(req: HttpRequest, res: HttpResponse, id: String, head: Boolean) {
        val (file, type) = store.media(id) ?: throw HttpError(404, "Not found")
        val len = file.length()
        val range = parseRange(req.header("range"), len)
        if (range == INVALID_RANGE) {
            res.send(416, "text/plain", ByteArray(0), mapOf("Content-Range" to "bytes */$len"))
            return
        }
        val (start, end) = range ?: (0L to len - 1)
        val count = if (len == 0L) 0 else end - start + 1
        val headers = mutableMapOf("Accept-Ranges" to "bytes", "Cache-Control" to "private, max-age=31536000, immutable")
        if (range != null) headers["Content-Range"] = "bytes $start-$end/$len"
        val out = res.start(if (range != null) 206 else 200, type, count, headers)
        if (head || count == 0L) return
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(256 * 1024)
            var left = count
            while (left > 0) {
                val r = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) break
                out.write(buf, 0, r)
                left -= r
            }
        }
        out.flush()
    }
}

internal val INVALID_RANGE = -1L to -1L

/** Parses a single `bytes=` range. null = no (usable) range header; [INVALID_RANGE] = unsatisfiable. */
internal fun parseRange(header: String?, len: Long): Pair<Long, Long>? {
    if (header == null || !header.startsWith("bytes=") || ',' in header || len <= 0) return null
    val spec = header.removePrefix("bytes=").trim()
    val a = spec.substringBefore('-').trim()
    val b = spec.substringAfter('-', "").trim()
    return when {
        a.isEmpty() && b.isNotEmpty() -> {
            val n = b.toLongOrNull() ?: return null
            if (n <= 0) INVALID_RANGE else maxOf(0, len - n) to len - 1
        }
        a.isNotEmpty() -> {
            val s = a.toLongOrNull() ?: return null
            val e = if (b.isEmpty()) len - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(len - 1)
            if (s >= len || e < s) INVALID_RANGE else s to e
        }
        else -> null
    }
}
