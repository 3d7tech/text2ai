package com.offgrid.meshpoc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.offgrid.mesh.ChatMessage
import com.offgrid.mesh.MeshNode
import com.offgrid.mesh.NodeId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Process-wide holder for the mesh node, shared by [MeshService] and [MainActivity]. */
object Mesh : MeshNode.Listener {
    private var node: MeshNode? = null
    private val main = Handler(Looper.getMainLooper())
    private val uiListeners = CopyOnWriteArrayList<MeshNode.Listener>()
    val history = CopyOnWriteArrayList<ChatMessage>()

    @Synchronized
    fun node(context: Context): MeshNode {
        node?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences("mesh", Context.MODE_PRIVATE)
        val id = prefs.getString("id", null)?.let(NodeId::parse)
            ?: NodeId.random().also { prefs.edit().putString("id", it.hex()).apply() }
        val nick = prefs.getString("nick", null) ?: "user-${id.hex().take(4)}"
        return MeshNode(id, nick, Executors.newSingleThreadExecutor()).also {
            it.addListener(this)
            node = it
        }
    }

    fun setNickname(context: Context, nick: String) {
        node(context).nickname = nick
        context.applicationContext.getSharedPreferences("mesh", Context.MODE_PRIVATE)
            .edit().putString("nick", nick).apply()
    }

    // ---------------------------------------------------------------- household hub

    @Volatile var hub: HubBridge? = null
        private set

    fun hubEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences("mesh", Context.MODE_PRIVATE).getBoolean("hubEnabled", false)

    /** Remembers the choice and starts/stops the hub (which lives as long as [MeshService]). */
    @Synchronized
    fun setHubEnabled(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences("mesh", Context.MODE_PRIVATE)
            .edit().putBoolean("hubEnabled", on).apply()
        if (on) startHub(context) else stopHub()
    }

    @Synchronized
    fun startHub(context: Context) {
        if (hub != null) return
        hub = HubBridge(context.applicationContext, node(context)).also { it.start() }
    }

    @Synchronized
    fun stopHub() {
        hub?.stop()
        hub = null
    }

    fun addUiListener(l: MeshNode.Listener) { uiListeners += l }
    fun removeUiListener(l: MeshNode.Listener) { uiListeners -= l }

    override fun onMessage(message: ChatMessage) {
        history += message
        main.post { uiListeners.forEach { it.onMessage(message) } }
    }

    override fun onNeighborsChanged(neighbors: List<String>) {
        main.post { uiListeners.forEach { it.onNeighborsChanged(neighbors) } }
    }
}
