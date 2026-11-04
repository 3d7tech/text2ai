package com.offgrid.hubapp

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.offgrid.mesh.hub.HubServer
import com.offgrid.mesh.hub.HubStore
import com.offgrid.mesh.hub.defaultHubDir
import com.offgrid.mesh.hub.householdKey
import com.offgrid.mesh.share.LocalAddresses
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * Family Chat Hub for Mac / Windows / Linux: double-click, and a window shows the QR code
 * the other devices scan. No terminal. Falls back to text mode when there is no display.
 */
fun main(args: Array<String>) {
    if (GraphicsEnvironment.isHeadless() || "--nogui" in args) {
        com.offgrid.mesh.hub.main(args.filter { it != "--nogui" }.toTypedArray())
        return
    }
    System.setProperty("apple.awt.application.name", "Family Chat Hub")
    System.setProperty("apple.awt.application.appearance", "system")
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    val dir = defaultHubDir().apply { mkdirs() }
    val key = householdKey(dir)
    val hub = HubServer(HubStore(dir), key)
    val port = try {
        hub.start(HubServer.DEFAULT_PORT)
    } catch (e: Exception) {
        SwingUtilities.invokeLater { HubWindow.error("Family Chat Hub couldn't start: ${e.message}") }
        return
    }
    keepAwake()
    Runtime.getRuntime().addShutdownHook(Thread { hub.stop() })
    SwingUtilities.invokeLater { HubWindow(hub, key, port, dir).show() }
}

/**
 * macOS: stop the Mac sleeping while the hub runs (the lid must stay open), and turn off
 * App Nap, which otherwise slows a background app's network replies by seconds.
 */
private fun keepAwake() {
    val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    if (!mac) return
    runCatching {
        ProcessBuilder("caffeinate", "-i", "-w", ProcessHandle.current().pid().toString())
            .redirectErrorStream(true).start()
    }
    runCatching {
        ProcessBuilder("defaults", "write", "com.offgrid.familychathub", "NSAppSleepDisabled", "-bool", "YES")
            .redirectErrorStream(true).start()
    }
}

private class HubWindow(private val hub: HubServer, private val key: String, private val port: Int, private val dir: File) {

    companion object {
        private val GREEN = Color(0x0F, 0x7A, 0x55)
        private val MUTED = Color(0x5C, 0x63, 0x5E)

        fun error(msg: String) {
            val f = JFrame("Family Chat Hub")
            f.contentPane.add(JLabel("<html><body style='width:320px;padding:16px'>$msg</body></html>"))
            f.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            f.pack(); f.setLocationRelativeTo(null); f.isVisible = true
        }
    }

    private val frame = JFrame("Family Chat Hub")
    private val status = JLabel()
    private val qr = JLabel()
    private val link = JTextField()
    private val note = JLabel()
    private val count = JLabel()
    private var shownUrl: String? = null

    private fun localUrl() = "http://localhost:$port/?k=$key"

    /** Address other devices on the Wi-Fi use; null if this computer isn't on a network. */
    private fun lanUrl(): String? {
        // -Dhub.address=192.168.1.23 overrides detection (e.g. a computer on both Ethernet and Wi-Fi)
        val forced = System.getProperty("hub.address")?.takeIf { it.isNotBlank() }
        val ip = forced ?: LocalAddresses.siteLocalIpv4(preferHotspot = false)
            .map { it.ip }
            .firstOrNull { !it.startsWith("100.115.92.") }
        return ip?.let { "http://$it:$port/?k=$key" }
    }

    fun show() {
        val base = frame.font?.size?.toFloat() ?: 13f
        fun label(text: String, size: Float = base + 1, bold: Boolean = false, color: Color? = null) = JLabel(text).apply {
            font = font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, size)
            color?.let { foreground = it }
            alignmentX = JComponent.CENTER_ALIGNMENT
            horizontalAlignment = SwingConstants.CENTER
        }

        val title = label("Family Chat Hub", base + 9, bold = true)
        status.apply {
            font = font.deriveFont(Font.BOLD, base + 2); foreground = GREEN
            alignmentX = JComponent.CENTER_ALIGNMENT
        }
        val instruction = label("<html><div style='text-align:center;width:330px'>On each phone, tablet or laptop: join this Wi-Fi, then scan this code with the camera.</div></html>")
        qr.alignmentX = JComponent.CENTER_ALIGNMENT
        Dimension(300, 300).let { qr.preferredSize = it; qr.minimumSize = it; qr.maximumSize = it }
        qr.horizontalAlignment = SwingConstants.CENTER
        qr.border = BorderFactory.createEmptyBorder(10, 0, 10, 0)
        link.apply {
            isEditable = false
            horizontalAlignment = JTextField.CENTER
            font = Font(Font.MONOSPACED, Font.PLAIN, (base).toInt())
            maximumSize = Dimension(400, preferredSize.height + 6)
            alignmentX = JComponent.CENTER_ALIGNMENT
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }
        val open = JButton("Open Family Chat").apply {
            font = font.deriveFont(Font.BOLD, base + 1)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { runCatching { Desktop.getDesktop().browse(URI(localUrl())) } }
        }
        val copy = JButton("Copy link").apply {
            addActionListener {
                val url = lanUrl() ?: localUrl()
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
                text = "Copied"
                Timer(1500) { text = "Copy link" }.apply { isRepeats = false; start() }
            }
        }
        val buttons = JPanel().apply {
            isOpaque = false
            add(open); add(copy)
            alignmentX = JComponent.CENTER_ALIGNMENT
            maximumSize = Dimension(420, preferredSize.height + 4)
        }
        count.apply { foreground = MUTED; alignmentX = JComponent.CENTER_ALIGNMENT }
        note.apply {
            foreground = MUTED
            font = font.deriveFont(base - 1)
            alignmentX = JComponent.CENTER_ALIGNMENT
            horizontalAlignment = SwingConstants.CENTER
            text = "<html><div style='text-align:center;width:340px'>Keep this window open and the computer plugged in" +
                " with the lid open. Closing the window stops the hub.<br>Chats, photos and videos are saved in " +
                "<i>${dir.absolutePath.replace(System.getProperty("user.home"), "~")}</i></div></html>"
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(22, 24, 20, 24)
            add(title); add(Box.createVerticalStrut(6))
            add(status); add(Box.createVerticalStrut(14))
            add(instruction)
            add(qr)
            add(link); add(Box.createVerticalStrut(10))
            add(buttons); add(Box.createVerticalStrut(8))
            add(count); add(Box.createVerticalStrut(14))
            add(note)
        }
        frame.contentPane.add(content, BorderLayout.CENTER)
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                frame.isVisible = false
                hub.stop()
                exitProcess(0)
            }
        })
        refresh()
        Timer(4000) { refresh() }.start()
        frame.pack()
        frame.minimumSize = frame.size
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }

    private fun refresh() {
        val url = lanUrl()
        if (url == null) {
            status.text = "Connect this computer to Wi-Fi"
            status.foreground = Color(0x9A, 0x5B, 0x00)
            qr.icon = null
            link.text = "The router doesn't need internet."
            shownUrl = null
        } else {
            status.text = "● Hub is on"
            status.foreground = GREEN
            if (url != shownUrl) {
                shownUrl = url
                qr.icon = ImageIcon(qrImage(url, 300))
                link.text = url
            }
        }
        val n = messageCount()
        count.text = if (n == 0L) "No messages yet" else "$n message${if (n == 1L) "" else "s"} so far"
    }

    /** One line per message in the hub's log. */
    private fun messageCount(): Long =
        runCatching { File(dir, "messages.tsv").takeIf { it.isFile }?.useLines { it.count().toLong() } }.getOrNull() ?: 0L

    private fun qrImage(text: String, size: Int): BufferedImage {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) for (x in 0 until size) img.setRGB(x, y, if (m.get(x, y)) 0x000000 else 0xFFFFFF)
        return img
    }
}
