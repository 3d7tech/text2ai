package com.offgrid.mesh.share

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds the `WIFI:` payload that Android/iOS cameras understand for "scan to join Wi-Fi". */
object WifiQr {
    private fun esc(s: String) = buildString {
        for (ch in s) {
            if (ch == '\\' || ch == ';' || ch == ',' || ch == ':' || ch == '"') append('\\')
            append(ch)
        }
    }

    fun payload(ssid: String, password: String?): String =
        if (password.isNullOrEmpty()) "WIFI:T:nopass;S:${esc(ssid)};;"
        else "WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};;"
}

object ApkPackager {
    /**
     * Wraps the APK in a zip. Some Bluetooth stacks refuse to send or receive `.apk` files but
     * accept `.zip`; the receiver opens the zip in their Files app and taps the APK inside.
     */
    fun zip(apk: File, out: File, entryName: String = apk.name): File {
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry(entryName))
            apk.inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
        return out
    }
}

object LocalAddresses {
    data class Address(val iface: String, val ip: String)

    private val skip = listOf("lo", "rmnet", "ccmni", "dummy", "tun", "v4-", "ip6tnl", "p2p")
    private val hotspotHints = listOf("ap", "swlan", "softap", "wlan1", "wigig")

    /** Private IPv4 addresses of this device, hotspot-looking interfaces first. */
    fun siteLocalIpv4(): List<Address> {
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        return all
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filter { nif -> skip.none { nif.name.startsWith(it) } }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { Address(nif.name, it.hostAddress) }
            }
            .sortedBy { a -> if (hotspotHints.any { a.iface.startsWith(it) }) 0 else if (a.iface.startsWith("wlan")) 1 else 2 }
    }
}
