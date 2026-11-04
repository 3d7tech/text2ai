package com.offgrid.meshpoc

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.offgrid.mesh.share.ApkWebServer
import com.offgrid.mesh.share.LocalAddresses
import com.offgrid.mesh.share.WifiQr

/**
 * "Give the app to someone who doesn't have it", with no internet and no app needed on their side.
 *
 * 1. Starts a local-only hotspot on this phone (no internet is shared) and shows a Wi-Fi QR code.
 * 2. Serves the APK over HTTP on that hotspot and shows a second QR code with the address.
 * The other phone scans QR 1 to join, scans QR 2 to open the page in its browser, and installs.
 *
 * If the hotspot can't start automatically, the user can turn the hotspot on by hand (or join
 * the same Wi-Fi network); the web server is reachable on any local network either way.
 */
@SuppressLint("MissingPermission", "SetTextI18n")
class ShareNearbyActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var server: ApkWebServer? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var shownUrl: String? = null

    private lateinit var wifiText: TextView
    private lateinit var wifiQr: ImageView
    private lateinit var urlText: TextView
    private lateinit var urlQr: ImageView
    private lateinit var countText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Share the app nearby"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val qrSize = (240 * d).toInt()

        fun heading(t: String) = TextView(this).apply {
            text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, pad / 3)
        }
        fun qrView() = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

        wifiText = TextView(this).apply { text = "Starting hotspot…" }
        wifiQr = qrView()
        urlText = TextView(this).apply { text = "Starting…" }
        urlQr = qrView()
        countText = TextView(this).apply { setPadding(0, pad, 0, 0) }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
            addView(TextView(this@ShareNearbyActivity).apply {
                text = "The other phone doesn't need the app, Bluetooth pairing or internet. " +
                    "Keep this screen open while they download."
            })
            addView(heading("1. Join this phone's Wi-Fi"))
            addView(wifiText)
            addView(wifiQr)
            addView(heading("2. Open the download page"))
            addView(urlText)
            addView(urlQr)
            addView(countText)
            addView(Button(this@ShareNearbyActivity).apply { text = "Done"; setOnClickListener { finish() } })
        }
        setContentView(ScrollView(this).apply { addView(content) })

        server = ApkWebServer(ApkShare.apkFile(this), ApkShare.FILE_NAME).also { it.start(8080) }
        startHotspot()
        handler.post(refresh)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        runCatching { reservation?.close() }
        super.onDestroy()
    }

    private fun startHotspot() {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        if (wm == null) { manualHotspot("Wi-Fi isn't available on this device."); return }
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    val (ssid, pass) = credentials(r)
                    if (ssid == null) { manualHotspot("Hotspot started but its name is hidden."); return }
                    wifiText.text = "Scan with the camera (or Wi-Fi settings → QR icon).\n" +
                        "Or join manually:  $ssid   password:  ${pass ?: "(none)"}"
                    wifiQr.setImageBitmap(qr(WifiQr.payload(ssid, pass)))
                }

                override fun onFailed(reason: Int) = manualHotspot("Couldn't start a hotspot automatically (code $reason).")

                override fun onStopped() { wifiText.text = "Hotspot stopped." ; wifiQr.setImageDrawable(null) }
            }, handler)
        } catch (e: Exception) {
            manualHotspot("Couldn't start a hotspot automatically (${e.javaClass.simpleName}).")
        }
    }

    private fun credentials(r: WifiManager.LocalOnlyHotspotReservation): Pair<String?, String?> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = r.softApConfiguration
            @Suppress("DEPRECATION") val ssid = c.ssid
            ssid to c.passphrase
        } else {
            @Suppress("DEPRECATION") val w = r.wifiConfiguration
            w?.SSID?.trim('"') to w?.preSharedKey?.trim('"')
        }

    private fun manualHotspot(why: String) {
        wifiText.text = "$why\n\nTurn on Hotspot from quick settings and have the other phone join it, " +
            "or connect both phones to the same Wi-Fi network. Then use step 2."
        wifiQr.setImageDrawable(null)
    }

    /** The hotspot's address appears a moment after it starts, so poll for it. */
    private val refresh = object : Runnable {
        override fun run() {
            val port = server?.port ?: -1
            val addrs = LocalAddresses.siteLocalIpv4()
            if (addrs.isEmpty() || port <= 0) {
                urlText.text = "Waiting for a local network…"
                urlQr.setImageDrawable(null)
                shownUrl = null
            } else {
                val urls = addrs.map { "http://${it.ip}:$port/" }
                urlText.text = "Scan, or type into the browser:\n" + urls.joinToString("\n")
                if (urls.first() != shownUrl) {
                    shownUrl = urls.first()
                    urlQr.setImageBitmap(qr(urls.first()))
                }
            }
            val n = server?.downloads ?: 0
            countText.text = if (n == 0) "No downloads yet." else "Downloaded $n time${if (n == 1) "" else "s"}."
            handler.postDelayed(this, 2_000)
        }
    }

    private fun qr(text: String): Bitmap {
        val size = 512
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
