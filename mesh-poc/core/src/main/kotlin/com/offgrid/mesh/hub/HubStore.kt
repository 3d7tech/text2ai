package com.offgrid.mesh.hub

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/** Anything that happens in the household chat, in one ordered log (`seq` increases by one each time). */
sealed interface HubEvent {
    val seq: Long
    val ts: Long
    fun toJson(): String
}

data class HubMessage(
    override val seq: Long,
    override val ts: Long,
    val from: String,
    val text: String,
    val mediaId: String?,
    val mediaType: String?,
    /** "web" (browser on the household Wi-Fi) or "mesh" (Bluetooth / Wi-Fi Aware phones). */
    val origin: String,
    val clientId: String,
    /** Small preview of a photo or video, shown in the chat list (the full file opens on tap). */
    val thumbId: String? = null,
    val room: String = HubStore.GENERAL,
    /** Deleted for everyone: text and media are gone from the hub, only this stub remains. */
    val deleted: Boolean = false,
) : HubEvent {
    override fun toJson(): String =
        """{"type":"msg","seq":$seq,"ts":$ts,"from":${jsonString(from)},"text":${jsonString(text)},""" +
            """"media":${jsonString(mediaId)},"mediaType":${jsonString(mediaType)},""" +
            """"origin":${jsonString(origin)},"cid":${jsonString(clientId)},"thumb":${jsonString(thumbId)},""" +
            """"room":${jsonString(room)},"deleted":$deleted}"""
}

/** A message was deleted for everyone. */
data class HubDelete(override val seq: Long, override val ts: Long, val target: Long, val by: String) : HubEvent {
    override fun toJson(): String = """{"type":"del","seq":$seq,"ts":$ts,"target":$target}"""
}

data class HubRoom(val id: String, val name: String, val createdBy: String, val ts: Long) {
    fun toJson(): String =
        """{"id":${jsonString(id)},"name":${jsonString(name)},"by":${jsonString(createdBy)},"ts":$ts}"""
}

/** A room was created or deleted. */
data class HubRoomEvent(override val seq: Long, override val ts: Long, val room: HubRoom, val created: Boolean) : HubEvent {
    override fun toJson(): String =
        """{"type":"room","seq":$seq,"ts":$ts,"action":"${if (created) "create" else "delete"}","room":${room.toJson()}}"""
}

/**
 * Durable household chat log + media files on the hub device.
 * Events are appended to a TSV log (URL-encoded fields); media files live in `media/`.
 * Deleting a message rewrites the log without its text and removes its files.
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
        const val MAX_ROOMS = 100
        /** The room everyone is in; it always exists and can't be deleted. */
        const val GENERAL = "general"
        private val ID = Regex("[0-9a-f]{16}")
        private val ROOM_ID = Regex("[0-9a-f]{8}|general")
        private val rng = SecureRandom()
    }

    private val lock = Object()
    private val log = ArrayList<HubEvent>()
    private val rooms = LinkedHashMap<String, HubRoom>()
    private val logFile = File(dir, "messages.tsv")
    private val mediaDir = File(dir, "media")

    /** Called (on the adding thread) for every new message, after it is stored. */
    val listeners = CopyOnWriteArrayList<(HubMessage) -> Unit>()

    init {
        mediaDir.mkdirs()
        rooms[GENERAL] = HubRoom(GENERAL, "Everyone", "", 0)
        if (logFile.exists()) {
            logFile.forEachLine { line -> decode(line)?.let { apply(it) } }
        }
        mediaDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun apply(e: HubEvent) {
        log += e
        if (e is HubRoomEvent) {
            if (e.created) rooms[e.room.id] = e.room else rooms.remove(e.room.id)
        }
    }

    val latestSeq: Long get() = synchronized(lock) { log.lastOrNull()?.seq ?: 0 }

    private fun nextSeq() = (log.lastOrNull()?.seq ?: 0) + 1

    /** Active rooms, the shared one first. */
    fun rooms(): List<HubRoom> = synchronized(lock) { rooms.values.toList() }

    fun add(
        from: String,
        text: String,
        mediaId: String?,
        mediaType: String?,
        origin: String,
        clientId: String,
        thumbId: String? = null,
        room: String = GENERAL,
    ): HubMessage {
        val name = from.trim().take(MAX_NAME).ifEmpty { "Someone" }
        val body = text.take(MAX_TEXT)
        require(body.isNotBlank() || mediaId != null) { "empty message" }
        require(mediaId == null || media(mediaId) != null) { "unknown media" }
        require(thumbId == null || media(thumbId) != null) { "unknown thumbnail" }
        val m: HubMessage
        synchronized(lock) {
            require(rooms.containsKey(room)) { "unknown room" }
            m = HubMessage(nextSeq(), clock(), name, body, mediaId, mediaType, origin, clientId.take(64), thumbId, room)
            append(m)
        }
        listeners.forEach { runCatching { it(m) } }
        return m
    }

    /** Deletes a message for everyone. Only its sender may. */
    fun deleteMessage(seq: Long, by: String): HubDelete {
        synchronized(lock) {
            val i = log.indexOfFirst { it is HubMessage && it.seq == seq }
            if (i < 0) throw NoSuchElementException("message not found")
            val m = log[i] as HubMessage
            if (m.deleted) throw NoSuchElementException("message already deleted")
            if (m.clientId.isEmpty() || m.clientId != by) throw SecurityException("only the sender can delete this message")
            listOfNotNull(m.mediaId, m.thumbId).forEach { id ->
                File(mediaDir, id).delete(); File(mediaDir, "$id.type").delete()
            }
            log[i] = m.copy(text = "", mediaId = null, mediaType = null, thumbId = null, deleted = true)
            val d = HubDelete(nextSeq(), clock(), seq, by.take(64))
            log += d
            rewrite() // so the deleted text is gone from disk too
            lock.notifyAll()
            return d
        }
    }

    fun createRoom(name: String, by: String): HubRoom {
        val n = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)
        require(n.isNotEmpty()) { "room name required" }
        synchronized(lock) {
            require(rooms.size < MAX_ROOMS) { "too many rooms" }
            require(rooms.values.none { it.name.equals(n, ignoreCase = true) }) { "a room with that name exists" }
            var id: String
            do { id = ByteArray(4).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) } } while (rooms.containsKey(id))
            val r = HubRoom(id, n, by.take(64), clock())
            append(HubRoomEvent(nextSeq(), r.ts, r, created = true))
            return r
        }
    }

    /** Deletes a room for everyone. Only its creator may; the shared room can't be deleted. */
    fun deleteRoom(id: String, by: String) {
        synchronized(lock) {
            val r = rooms[id] ?: throw NoSuchElementException("room not found")
            if (id == GENERAL) throw SecurityException("the shared room can't be deleted")
            if (r.createdBy.isEmpty() || r.createdBy != by) throw SecurityException("only the room's creator can delete it")
            append(HubRoomEvent(nextSeq(), clock(), r, created = false))
        }
    }

    private fun append(e: HubEvent) {
        apply(e)
        logFile.appendText(encode(e) + "\n")
        lock.notifyAll()
    }

    private fun rewrite() {
        val tmp = File(dir, "messages.tsv.tmp")
        tmp.bufferedWriter().use { w -> log.forEach { w.write(encode(it)); w.write("\n") } }
        if (!tmp.renameTo(logFile)) {
            logFile.delete()
            if (!tmp.renameTo(logFile)) throw IOException("could not rewrite the chat log")
        }
    }

    /** Events after [seq], skipping messages in rooms that have since been deleted. */
    fun after(seq: Long, limit: Int = 1000): List<HubEvent> = synchronized(lock) {
        val i = log.indexOfFirst { it.seq > seq }
        if (i < 0) emptyList()
        else log.subList(i, minOf(log.size, i + limit)).filter { !(it is HubMessage && !rooms.containsKey(it.room)) }
    }

    /** Blocks up to [timeoutMs] for events newer than [seq]. */
    fun awaitAfter(seq: Long, timeoutMs: Long): List<HubEvent> = synchronized(lock) {
        if ((log.lastOrNull()?.seq ?: 0) <= seq) (lock as Object).wait(timeoutMs)
        after(seq)
    }

    fun message(seq: Long): HubMessage? = synchronized(lock) { log.firstOrNull { it is HubMessage && it.seq == seq } as HubMessage? }

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

    fun isRoomId(id: String) = ROOM_ID.matches(id)

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

    private fun encode(e: HubEvent): String = when (e) {
        is HubMessage -> listOf(
            e.seq.toString(), e.ts.toString(), enc(e.from), enc(e.text), enc(e.mediaId), enc(e.mediaType),
            enc(e.origin), enc(e.clientId), enc(e.thumbId), enc(e.room), if (e.deleted) "1" else "0",
        ).joinToString("\t")
        is HubDelete -> listOf(e.seq.toString(), "#del", e.ts.toString(), e.target.toString(), enc(e.by)).joinToString("\t")
        is HubRoomEvent -> listOf(
            e.seq.toString(), "#room", e.ts.toString(), enc(e.room.id), enc(e.room.name), enc(e.room.createdBy),
            e.room.ts.toString(), if (e.created) "create" else "delete",
        ).joinToString("\t")
    }

    private fun decode(line: String): HubEvent? = runCatching {
        val p = line.split('\t')
        when {
            p.size >= 2 && p[1] == "#del" && p.size == 5 -> HubDelete(p[0].toLong(), p[2].toLong(), p[3].toLong(), dec(p[4]) ?: "")
            p.size >= 2 && p[1] == "#room" && p.size == 8 ->
                HubRoomEvent(p[0].toLong(), p[2].toLong(), HubRoom(dec(p[3])!!, dec(p[4])!!, dec(p[5]) ?: "", p[6].toLong()), p[7] == "create")
            // 8 fields: before thumbnails; 9: before rooms; 11: current
            p.size == 8 || p.size == 9 || p.size == 11 -> HubMessage(
                p[0].toLong(), p[1].toLong(), dec(p[2])!!, dec(p[3]) ?: "", dec(p[4]), dec(p[5]), dec(p[6]) ?: "web", dec(p[7]) ?: "",
                p.getOrNull(8)?.let(::dec), p.getOrNull(9)?.let(::dec) ?: GENERAL, p.getOrNull(10) == "1",
            )
            else -> null
        }
    }.getOrNull()
}
