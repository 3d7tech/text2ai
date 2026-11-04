package com.offgrid.mesh.hub

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

data class HubMessage(
    val seq: Long,
    val ts: Long,
    val from: String,
    val text: String,
    val mediaId: String?,
    val mediaType: String?,
    /** "web" (browser on the household Wi-Fi) or "mesh" (Bluetooth / Wi-Fi Aware phones). */
    val origin: String,
    val clientId: String,
) {
    fun toJson(): String =
        """{"seq":$seq,"ts":$ts,"from":${jsonString(from)},"text":${jsonString(text)},""" +
            """"media":${jsonString(mediaId)},"mediaType":${jsonString(mediaType)},""" +
            """"origin":${jsonString(origin)},"cid":${jsonString(clientId)}}"""
}

/**
 * Durable household chat log + media files on the hub device.
 * Messages are appended to a TSV log (URL-encoded fields), media files live in `media/`.
 */
class HubStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    val maxMediaBytes: Long = 300L * 1024 * 1024,
    private val maxTotalMediaBytes: Long = 4L * 1024 * 1024 * 1024,
) {
    companion object {
        const val MAX_NAME = 40
        const val MAX_TEXT = 4000
        private val ID = Regex("[0-9a-f]{16}")
        private val rng = SecureRandom()
    }

    private val lock = Object()
    private val messages = ArrayList<HubMessage>()
    private val log = File(dir, "messages.tsv")
    private val mediaDir = File(dir, "media")

    /** Called (on the adding thread) for every new message, after it is stored. */
    val listeners = CopyOnWriteArrayList<(HubMessage) -> Unit>()

    init {
        mediaDir.mkdirs()
        if (log.exists()) {
            log.forEachLine { line -> decode(line)?.let { messages += it } }
        }
        mediaDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    val latestSeq: Long get() = synchronized(lock) { messages.lastOrNull()?.seq ?: 0 }

    fun add(from: String, text: String, mediaId: String?, mediaType: String?, origin: String, clientId: String): HubMessage {
        val name = from.trim().take(MAX_NAME).ifEmpty { "Someone" }
        val body = text.take(MAX_TEXT)
        require(body.isNotBlank() || mediaId != null) { "empty message" }
        require(mediaId == null || media(mediaId) != null) { "unknown media" }
        val m: HubMessage
        synchronized(lock) {
            m = HubMessage((messages.lastOrNull()?.seq ?: 0) + 1, clock(), name, body, mediaId, mediaType, origin, clientId.take(64))
            messages += m
            log.appendText(encode(m) + "\n")
            lock.notifyAll()
        }
        listeners.forEach { runCatching { it(m) } }
        return m
    }

    fun after(seq: Long, limit: Int = 1000): List<HubMessage> = synchronized(lock) {
        val i = messages.indexOfFirst { it.seq > seq }
        if (i < 0) emptyList() else messages.subList(i, minOf(messages.size, i + limit)).toList()
    }

    /** Blocks up to [timeoutMs] for messages newer than [seq]. */
    fun awaitAfter(seq: Long, timeoutMs: Long): List<HubMessage> = synchronized(lock) {
        if ((messages.lastOrNull()?.seq ?: 0) <= seq) (lock as Object).wait(timeoutMs)
        after(seq)
    }

    /** Streams exactly [length] bytes into a new media file. Returns its id. */
    fun saveMedia(input: InputStream, length: Long, mime: String): String {
        if (length > maxMediaBytes) throw HttpError(413, "File too large")
        val id = ByteArray(8).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val part = File(mediaDir, "$id.part")
        try {
            part.outputStream().buffered(256 * 1024).use { out ->
                val buf = ByteArray(256 * 1024)
                var left = length
                while (left > 0) {
                    val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) throw IOException("upload interrupted")
                    out.write(buf, 0, r)
                    left -= r
                }
            }
            File(mediaDir, "$id.type").writeText(mime)
            if (!part.renameTo(File(mediaDir, id))) throw IOException("could not store file")
        } catch (e: Exception) {
            part.delete()
            File(mediaDir, "$id.type").delete()
            throw e
        }
        enforceQuota()
        return id
    }

    fun media(id: String): Pair<File, String>? {
        if (!ID.matches(id)) return null
        val f = File(mediaDir, id)
        if (!f.isFile) return null
        val type = File(mediaDir, "$id.type").takeIf { it.isFile }?.readText() ?: "application/octet-stream"
        return f to type
    }

    private fun enforceQuota() {
        val files = mediaDir.listFiles { f -> ID.matches(f.name) }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxTotalMediaBytes) break
            total -= f.length()
            f.delete()
            File(mediaDir, "${f.name}.type").delete()
        }
    }

    private fun enc(s: String?) = if (s == null) "-" else URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = if (s == "-") null else URLDecoder.decode(s, "UTF-8")

    private fun encode(m: HubMessage) =
        listOf(m.seq.toString(), m.ts.toString(), enc(m.from), enc(m.text), enc(m.mediaId), enc(m.mediaType), enc(m.origin), enc(m.clientId))
            .joinToString("\t")

    private fun decode(line: String): HubMessage? = runCatching {
        val p = line.split('\t')
        if (p.size != 8) return null
        HubMessage(p[0].toLong(), p[1].toLong(), dec(p[2])!!, dec(p[3]) ?: "", dec(p[4]), dec(p[5]), dec(p[6]) ?: "web", dec(p[7]) ?: "")
    }.getOrNull()
}
