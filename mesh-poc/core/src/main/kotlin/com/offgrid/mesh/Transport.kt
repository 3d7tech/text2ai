package com.offgrid.mesh

/**
 * A radio that can reach some neighbors (BLE, Wi-Fi Aware, LoRa, ...).
 *
 * `link` is an opaque, transport-local neighbor handle (e.g. a BLE MAC address).
 * Transports deliver raw frames; fragmentation, de-duplication and routing live in [MeshNode].
 */
interface Transport {
    val name: String

    fun start(listener: TransportListener)
    fun stop()

    /** Queue one frame (<= [maxFrameSize]) for a neighbor. Returns false if the link is gone. */
    fun send(link: String, frame: ByteArray): Boolean

    fun maxFrameSize(link: String): Int
}

interface TransportListener {
    /** A neighbor we can *send* to has appeared. */
    fun onNeighborUp(transport: Transport, link: String)
    fun onNeighborDown(transport: Transport, link: String)
    fun onFrame(transport: Transport, link: String, frame: ByteArray)
}
