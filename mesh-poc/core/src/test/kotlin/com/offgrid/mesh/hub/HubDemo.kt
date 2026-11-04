package com.offgrid.mesh.hub

import java.io.File

/** Runs a hub on the JVM for browser testing: gradle -p core runHubDemo */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "build/hub-demo" }).apply { mkdirs() }
    val key = args.getOrElse(1) { "demokey123" }
    val port = args.getOrElse(2) { "8090" }.toInt()
    val hub = HubServer(HubStore(dir), key)
    val p = hub.start(port)
    println("HUB READY http://127.0.0.1:$p/?k=$key")
    Thread.currentThread().join()
}
