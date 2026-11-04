package com.offgrid.meshpoc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.offgrid.mesh.ChatMessage
import com.offgrid.mesh.MeshNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal chat UI (built in code, no XML/AndroidX, to keep the PoC small).
 *
 * Commands:  `/nick <name>`   `/dm <nick> <text>`   `/peers`
 */
class MainActivity : Activity(), MeshNode.Listener {

    private lateinit var status: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var input: EditText
    private val time = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val node get() = Mesh.node(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (12 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply { setPadding(pad, pad, pad / 2, pad / 2) }
        val shareButton = Button(this).apply { text = "Share app"; setOnClickListener { showShareOptions() } }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, pad, 0)
            addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(shareButton)
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
            isStackFromBottom = true
        }
        input = EditText(this).apply {
            hint = "Message everyone nearby…"
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine()
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        val send = Button(this).apply { text = "Send"; setOnClickListener { submit() } }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 0, pad, pad)
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(row)
        })

        Mesh.history.forEach { adapter.add(format(it)) }
        Mesh.addUiListener(this)
        refreshStatus(node.neighborSnapshot)
        requestPermissionsThenStart()
    }

    override fun onDestroy() {
        Mesh.removeUiListener(this)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- permissions

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun requestPermissionsThenStart() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startMesh() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Notifications are optional; radios are not.
        val denied = permissions.indices.filter {
            grantResults[it] != PackageManager.PERMISSION_GRANTED &&
                permissions[it] != Manifest.permission.POST_NOTIFICATIONS
        }
        if (denied.isEmpty()) startMesh() else status.text = "Radio permissions are required to join the mesh."
    }

    @Suppress("MissingPermission")
    private fun startMesh() {
        val bt = getSystemService(BluetoothManager::class.java)?.adapter
        if (bt != null && !bt.isEnabled) startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        startForegroundService(Intent(this, MeshService::class.java))
    }

    // ---------------------------------------------------------------- sharing the app

    private fun showShareOptions() {
        val options = arrayOf(
            "Bluetooth, Quick Share, Xender, SHAREit, WhatsApp…",
            "Bluetooth as .zip (if Bluetooth refuses the app file)",
            "Phone without the app: Wi-Fi + QR code (no internet)",
            "Send the download link (needs internet)",
        )
        AlertDialog.Builder(this)
            .setTitle("Share Offgrid Mesh")
            .setItems(options) { _, which ->
                try {
                    when (which) {
                        0 -> ApkShare.shareApk(this)
                        1 -> ApkShare.shareZip(this)
                        2 -> startActivity(Intent(this, ShareNearbyActivity::class.java))
                        3 -> ApkShare.shareLink(this)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't share: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- chat

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        when {
            text.startsWith("/nick ") -> {
                Mesh.setNickname(this, text.removePrefix("/nick ").trim())
                refreshStatus(node.neighborSnapshot)
            }
            text == "/peers" -> {
                val peers = node.peers.entries.joinToString { "${it.value} (${it.key.hex().take(6)})" }
                adapter.add("— heard from: ${peers.ifEmpty { "nobody yet" }}")
            }
            text.startsWith("/dm ") -> {
                val parts = text.removePrefix("/dm ").split(" ", limit = 2)
                val target = node.peers.entries.firstOrNull { it.value.equals(parts[0], ignoreCase = true) }?.key
                if (target == null || parts.size < 2) adapter.add("— unknown peer '${parts[0]}' (try /peers)")
                else trySend { node.sendDirect(target, parts[1]) }
            }
            else -> trySend { node.sendBroadcast(text) }
        }
    }

    private fun trySend(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            adapter.add("— not sent: ${e.message}")
        }
    }

    override fun onMessage(message: ChatMessage) { adapter.add(format(message)) }

    override fun onNeighborsChanged(neighbors: List<String>) = refreshStatus(neighbors)

    private fun refreshStatus(neighbors: List<String>) {
        val byRadio = neighbors.groupingBy { it.substringBefore('/') }.eachCount()
        val radios = if (byRadio.isEmpty()) "no neighbors yet" else byRadio.entries.joinToString { "${it.key}: ${it.value}" }
        status.text = "You are ${node.nickname} · ${node.id.hex().take(6)}\nNeighbors — $radios"
    }

    private fun format(m: ChatMessage): String {
        val who = if (m.outgoing) "me" else m.nick
        val dm = if (m.isDirect) " [DM]" else ""
        val hops = if (m.outgoing) "" else " · ${m.hops + 1} hop${if (m.hops == 0) "" else "s"}"
        return "${time.format(Date(m.timestamp))}  $who$dm: ${m.text}$hops"
    }
}
