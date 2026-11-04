package com.offgrid.meshpoc

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.offgrid.mesh.Transport
import com.offgrid.mesh.TransportListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wi-Fi Aware (Neighbor Awareness Networking) transport.
 *
 * Each phone both **publishes** and **subscribes** to [SERVICE_NAME]. Frames are carried in
 * Aware *follow-up messages* (≈255 bytes each, no IP data path needed), which is plenty for text
 * chat and works with no access point, no internet and no pairing. Discovery range is typically
 * 50-100 m+, noticeably better than BLE.
 *
 * Outbound traffic is sent on the subscribe session to peers it discovered; inbound traffic
 * arrives on the publish session. For bulk data (photos, voice notes) the next step is a
 * Wi-Fi Aware *data path* (WifiAwareNetworkSpecifier + sockets), which gives Wi-Fi speeds.
 *
 * iOS 26 exposes Wi-Fi Aware to apps too, so this transport is the cross-platform
 * iPhone <-> Android link, unlike Wi-Fi Direct or Multipeer Connectivity.
 */
@SuppressLint("MissingPermission")
class WifiAwareTransport(private val context: Context) : Transport {

    companion object {
        private const val TAG = "WifiAwareTransport"
        const val SERVICE_NAME = "offgrid-mesh-poc"

        fun isSupported(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    }

    override val name = "aware"

    private val manager = context.getSystemService(WifiAwareManager::class.java)
    private val thread = HandlerThread("aware").apply { start() }
    private val handler = Handler(thread.looper)

    private var listener: TransportListener? = null
    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val peers = ConcurrentHashMap<String, PeerHandle>()
    private val messageIds = AtomicInteger()
    @Volatile private var maxMessage = 255
    @Volatile private var stopped = false

    override fun start(listener: TransportListener) {
        this.listener = listener
        context.registerReceiver(
            stateReceiver,
            IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED),
        )
        handler.post { if (manager?.isAvailable == true) attach() }
    }

    override fun stop() {
        stopped = true
        runCatching { context.unregisterReceiver(stateReceiver) }
        handler.post { teardown() }
        thread.quitSafely()
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            handler.post { if (manager?.isAvailable == true) attach() else teardown() }
        }
    }

    private fun attach() {
        if (session != null || stopped) return
        val m = manager ?: return
        m.attach(object : AttachCallback() {
            override fun onAttached(s: WifiAwareSession) {
                session = s
                m.characteristics?.maxServiceSpecificInfoLength?.let { maxMessage = it.coerceIn(32, 255) }
                s.publish(PublishConfig.Builder().setServiceName(SERVICE_NAME).build(), publishCallback, handler)
                s.subscribe(SubscribeConfig.Builder().setServiceName(SERVICE_NAME).build(), subscribeCallback, handler)
                Log.i(TAG, "attached, maxMessage=$maxMessage")
            }

            override fun onAttachFailed() {
                Log.w(TAG, "attach failed; retrying")
                handler.postDelayed({ attach() }, 5_000)
            }
        }, handler)
    }

    private fun teardown() {
        for (link in peers.keys) listener?.onNeighborDown(this, link)
        peers.clear()
        runCatching { publishSession?.close() }
        runCatching { subscribeSession?.close() }
        runCatching { session?.close() }
        publishSession = null
        subscribeSession = null
        session = null
    }

    private val publishCallback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(s: PublishDiscoverySession) { publishSession = s }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            listener?.onFrame(this@WifiAwareTransport, "pub:${peerHandle.hashCode()}", message)
        }

        override fun onSessionTerminated() { publishSession = null }
    }

    private val subscribeCallback = object : DiscoverySessionCallback() {
        override fun onSubscribeStarted(s: SubscribeDiscoverySession) { subscribeSession = s }

        override fun onServiceDiscovered(
            peerHandle: PeerHandle,
            serviceSpecificInfo: ByteArray?,
            matchFilter: List<ByteArray>?,
        ) {
            val link = "sub:${peerHandle.hashCode()}"
            if (peers.put(link, peerHandle) == null) listener?.onNeighborUp(this@WifiAwareTransport, link)
        }

        // API 33+. On older releases peers are only dropped when sends start failing.
        override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
            val link = "sub:${peerHandle.hashCode()}"
            if (peers.remove(link) != null) listener?.onNeighborDown(this@WifiAwareTransport, link)
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            listener?.onFrame(this@WifiAwareTransport, "sub:${peerHandle.hashCode()}", message)
        }

        override fun onMessageSendFailed(messageId: Int) { Log.d(TAG, "send failed: $messageId") }

        override fun onSessionTerminated() { subscribeSession = null }
    }

    override fun maxFrameSize(link: String): Int = maxMessage

    override fun send(link: String, frame: ByteArray): Boolean {
        val peer = peers[link] ?: return false
        val s = subscribeSession ?: return false
        handler.post { s.sendMessage(peer, messageIds.incrementAndGet(), frame) }
        return true
    }
}
