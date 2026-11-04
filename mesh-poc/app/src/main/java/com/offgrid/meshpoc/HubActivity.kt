package com.offgrid.meshpoc

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.offgrid.mesh.share.LocalAddresses

/**
 * Turns this device into the household hub: iPhones, Chromebooks and tablets on the same Wi-Fi
 * open the address in a browser (no internet needed) and add it to their home screen.
 */
@SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
class HubActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var toggle: Switch
    private lateinit var info: TextView
    private lateinit var qr: ImageView
    private lateinit var urls: TextView
    private lateinit var actions: LinearLayout
    private var shownUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Household hub"
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        fun heading(t: String) = TextView(this).apply {
            text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, pad / 3)
        }
        fun body(t: String) = TextView(this).apply { text = t; setPadding(0, 0, 0, pad / 3) }

        toggle = Switch(this).apply {
            text = "Run the hub on this device"
            textSize = 17f
            setPadding(0, pad / 2, 0, pad / 2)
            setOnCheckedChangeListener { _, on ->
                Mesh.setHubEnabled(this@HubActivity, on)
                startForegroundService(Intent(this@HubActivity, MeshService::class.java))
                refresh.run()
            }
        }
        info = body("")
        qr = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((260 * d).toInt(), (260 * d).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        urls = body("")
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@HubActivity).apply { text = "Open chat here"; setOnClickListener { openHere() } })
            addView(Button(this@HubActivity).apply { text = "Send link"; setOnClickListener { shareLink() } })
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
            addView(body("Lets iPhones, Chromebooks and tablets join Family Chat through this device. " +
                "Everyone just needs to be on the same Wi-Fi. The router doesn't need internet."))
            addView(toggle)
            addView(info)
            addView(qr)
            addView(urls)
            addView(actions)
            addView(heading("Set up each device"))
            addView(body("• iPhone / iPad: scan the code with the Camera, open it in Safari, then Share → Add to Home Screen."))
            addView(body("• Chromebook: open the link in Chrome, then ⋮ → Cast, save and share → Create shortcut… → tick \"Open as window\"."))
            addView(body("• Android tablet or phone: install this app, or open the link in Chrome → ⋮ → Add to Home screen."))
            addView(heading("Tips"))
            addView(body("• Keep this device plugged in, with Wi-Fi on. Settings → Apps → Offgrid Mesh PoC → Battery → Unrestricted."))
            addView(body("• If the router restarts, the address can change. Come back here and scan the new code."))
            addView(body("• Photos and videos are stored on this device (up to 4 GB; the oldest are removed first)."))
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        toggle.isChecked = Mesh.hubEnabled(this)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun currentUrl(): String? {
        val port = Mesh.hub?.port?.takeIf { it > 0 } ?: return null
        val ip = LocalAddresses.siteLocalIpv4(preferHotspot = false).firstOrNull()?.ip ?: return null
        return "http://$ip:$port/?k=${HubBridge.key(this)}"
    }

    private val refresh = object : Runnable {
        override fun run() {
            val on = Mesh.hubEnabled(this@HubActivity)
            val port = Mesh.hub?.port ?: -1
            val all = LocalAddresses.siteLocalIpv4(preferHotspot = false)
            val url = currentUrl()
            when {
                !on -> {
                    info.text = "The hub is off."
                    qr.setImageDrawable(null); urls.text = ""; shownUrl = null
                }
                port <= 0 -> info.text = "Starting…"
                url == null -> {
                    info.text = "Connect this device to your home Wi-Fi (internet not needed), or turn on its hotspot."
                    qr.setImageDrawable(null); urls.text = ""; shownUrl = null
                }
                else -> {
                    val msgs = Mesh.hub?.store?.latestSeq ?: 0
                    info.text = "Hub is running · $msgs messages so far. Scan this on each device:"
                    if (url != shownUrl) { shownUrl = url; qr.setImageBitmap(Qr.bitmap(url)) }
                    val key = HubBridge.key(this@HubActivity)
                    urls.text = "Or type the address:\n" + all.joinToString("\n") { "http://${it.ip}:$port/?k=$key" }
                }
            }
            actions.visibility = if (on && url != null) android.view.View.VISIBLE else android.view.View.GONE
            handler.postDelayed(this, 3_000)
        }
    }

    private fun openHere() {
        val port = Mesh.hub?.port ?: return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port/?k=${HubBridge.key(this)}")))
    }

    private fun shareLink() {
        val url = currentUrl() ?: return
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Join our Family Chat (on our home Wi-Fi): $url")
        startActivity(Intent.createChooser(i, "Send the Family Chat link"))
    }
}
