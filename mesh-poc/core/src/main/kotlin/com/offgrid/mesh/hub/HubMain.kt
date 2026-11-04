package com.offgrid.mesh.hub

import com.offgrid.mesh.share.LocalAddresses
import java.io.File

/**
 * Standalone household hub: `java -jar offgrid-hub.jar [--port 8090] [--dir ~/offgrid-hub] [--key KEY]`.
 * Made for a Chromebook's Linux environment, but runs on any computer with Java 17+.
 * The household key is created once and kept in the data folder, so the QR codes stay valid.
 */
fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a == "--help" || a == "-h") {
            println("Usage: java -jar offgrid-hub.jar [--port 8090] [--dir FOLDER] [--key KEY]")
            return
        }
        if (a.startsWith("--") && i + 1 < args.size) { opts[a.removePrefix("--")] = args[i + 1]; i += 2 } else i++
    }
    val dir = File(opts["dir"] ?: (System.getProperty("user.home") + File.separator + "offgrid-hub")).apply { mkdirs() }
    val keyFile = File(dir, "household.key")
    val key = opts["key"]
        ?: keyFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        ?: HubServer.newKey().also { keyFile.writeText(it) }
    if (opts["key"] != null) keyFile.writeText(key)

    val hub = HubServer(HubStore(dir), key)
    val port = hub.start(opts["port"]?.toIntOrNull() ?: HubServer.DEFAULT_PORT)
    Runtime.getRuntime().addShutdownHook(Thread { hub.stop() })

    val lan = LocalAddresses.siteLocalIpv4(preferHotspot = false)
        .map { it.ip }
        .filterNot { it.startsWith("100.115.92.") } // Chromebook's internal Linux network
    println(
        """
        |
        |  Family Chat hub is running  -  HUB READY
        |
        |  1. On this computer, open:   http://localhost:$port/?k=$key
        |  2. Tap "Invite" in the chat to show a QR code for the other devices.
        |${if (lan.isNotEmpty()) "  Other devices on your Wi-Fi can use:  " + lan.joinToString("  ") { "http://$it:$port/?k=$key" } + "\n|" else ""}
        |  On a Chromebook, also:
        |   - Settings > About ChromeOS > Developers > Linux > Port forwarding > Add $port (TCP) and switch it on.
        |   - Find this Chromebook's address: Settings > Network > Wi-Fi > your network > IP address.
        |   - Keep it plugged in, and keep this window open (closing it stops the hub).
        |
        |  Messages, photos and videos are saved in: ${dir.absolutePath}
        |  Press Ctrl+C to stop.
        |""".trimMargin(),
    )
    Thread.currentThread().join()
}
