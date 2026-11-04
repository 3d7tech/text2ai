package com.offgrid.meshpoc

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.offgrid.mesh.share.ApkPackager
import com.offgrid.mesh.share.ApkWebServer
import java.io.File

/** Shares this app's own installer so it can spread phone-to-phone without the internet. */
object ApkShare {
    const val FILE_NAME = "OffgridMesh.apk"
    const val ZIP_NAME = "OffgridMesh-apk.zip"
    const val PAGE_URL = "https://3d7tech.github.io/text2ai/"

    fun shareDir(context: Context) = File(context.cacheDir, "share").apply { mkdirs() }

    /** A copy of the installed APK under a friendly name (the original is called base.apk). */
    fun apkFile(context: Context): File {
        val src = File(context.applicationInfo.sourceDir)
        val out = File(shareDir(context), FILE_NAME)
        if (!out.exists() || out.length() != src.length() || out.lastModified() < src.lastModified()) {
            src.copyTo(out, overwrite = true)
        }
        return out
    }

    fun zipFile(context: Context): File {
        val apk = apkFile(context)
        val out = File(shareDir(context), ZIP_NAME)
        if (!out.exists() || out.lastModified() < apk.lastModified()) ApkPackager.zip(apk, out, FILE_NAME)
        return out
    }

    /** System share sheet: Bluetooth, Quick Share / Nearby Share, Xender, SHAREit, WhatsApp, … */
    fun shareApk(activity: Activity) {
        apkFile(activity)
        send(activity, ApkProvider.uriFor(activity, FILE_NAME), ApkWebServer.APK_MIME, "Share the app")
    }

    /** Same, but zipped: for Bluetooth stacks that reject .apk files. */
    fun shareZip(activity: Activity) {
        zipFile(activity)
        send(activity, ApkProvider.uriFor(activity, ZIP_NAME), "application/zip", "Send the app as .zip")
    }

    fun shareLink(activity: Activity) {
        val i = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Chat with no internet or mobile network. Get Offgrid Mesh: $PAGE_URL")
        activity.startActivity(Intent.createChooser(i, "Send download link"))
    }

    private fun send(activity: Activity, uri: android.net.Uri, mime: String, title: String) {
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(i, title))
    }
}
