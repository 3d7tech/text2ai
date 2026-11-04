package com.offgrid.meshpoc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.offgrid.mesh.share.ApkWebServer
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only provider exposing exactly two files (the APK and its zip) to apps the user shares
 * with. A minimal stand-in for androidx FileProvider, so the PoC stays dependency-free.
 */
class ApkProvider : ContentProvider() {

    companion object {
        fun authority(context: Context) = "${context.packageName}.share"
        fun uriFor(context: Context, name: String): Uri = Uri.parse("content://${authority(context)}/$name")
    }

    override fun onCreate(): Boolean = true

    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name != ApkShare.FILE_NAME && name != ApkShare.ZIP_NAME) return null
        val ctx = context ?: return null
        return File(ApkShare.shareDir(ctx), name).takeIf { it.exists() }
    }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment) {
        ApkShare.FILE_NAME -> ApkWebServer.APK_MIME
        ApkShare.ZIP_NAME -> "application/zip"
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = file(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val f = file(uri) ?: return null
        val cols = projection?.toList()?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map<String, Any?> {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }.toTypedArray()
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
