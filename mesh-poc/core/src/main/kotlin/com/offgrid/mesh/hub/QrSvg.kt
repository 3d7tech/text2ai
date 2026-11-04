package com.offgrid.mesh.hub

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Renders a QR code as a crisp, scalable SVG (black on white, so phone cameras read it in any theme). */
object QrSvg {
    fun svg(text: String): String {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
        val n = m.width
        val path = StringBuilder()
        for (y in 0 until m.height) for (x in 0 until n) if (m.get(x, y)) path.append("M$x ${y}h1v1h-1z")
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $n ${m.height}" shape-rendering="crispEdges">""" +
            """<rect width="100%" height="100%" fill="#fff"/><path fill="#000" d="$path"/></svg>"""
    }
}
