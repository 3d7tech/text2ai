package com.offgrid.meshpoc

import android.content.Context
import com.offgrid.mesh.ChatMessage
import com.offgrid.mesh.MeshNode
import com.offgrid.mesh.PacketId
import com.offgrid.mesh.hub.HubMessage
import com.offgrid.mesh.hub.HubServer
import com.offgrid.mesh.hub.HubStore
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the household [HubServer] on this device and bridges it to the radio mesh:
 * - text from browsers (iPhones, Chromebooks…) is re-broadcast on the mesh under the sender's name;
 *   photos/videos become a short note, since media stays on the hub;
 * - public mesh messages (from Android phones running the app) appear in the browsers.
 */
class HubBridge(context: Context, private val node: MeshNode) : MeshNode.Listener {

    companion object {
        fun key(context: Context): String {
            val prefs = context.getSharedPreferences("mesh", Context.MODE_PRIVATE)
            return prefs.getString("hubKey", null) ?: HubServer.newKey().also {
                prefs.edit().putString("hubKey", it).apply()
            }
        }
    }

    val store = HubStore(File(context.filesDir, "hub"))
    val server = HubServer(store, key(context), "Family Chat")
    private val bridged = Collections.newSetFromMap(ConcurrentHashMap<PacketId, Boolean>())

    val port: Int get() = server.port

    fun start(): Int {
        server.onWebMessage = { toMesh(it) }
        node.addListener(this)
        return server.start(HubServer.DEFAULT_PORT)
    }

    fun stop() {
        node.removeListener(this)
        server.onWebMessage = null
        server.stop()
    }

    private fun toMesh(m: HubMessage) {
        val note = when {
            m.mediaType?.startsWith("video/") == true -> "🎬 sent a video (open Family Chat to watch)"
            m.mediaType?.startsWith("audio/") == true -> "🎤 sent a voice note (open Family Chat to listen)"
            m.mediaId != null -> "📷 sent a photo (open Family Chat to see it)"
            else -> null
        }
        val text = listOfNotNull(m.text.takeIf { it.isNotBlank() }, note).joinToString("\n").take(1000)
        if (text.isEmpty()) return
        val id = PacketId.random()
        bridged += id
        runCatching { node.sendBroadcast(text, asNick = m.from, packetId = id) }
    }

    override fun onMessage(message: ChatMessage) {
        if (bridged.remove(message.id)) return // our own relay of a browser message
        if (message.isDirect) return // private messages stay on the mesh
        runCatching { store.add(message.nick, message.text, null, null, "mesh", "mesh:${message.from.hex()}") }
    }
}
