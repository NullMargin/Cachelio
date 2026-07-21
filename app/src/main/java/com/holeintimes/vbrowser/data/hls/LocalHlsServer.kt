package com.holeintimes.vbrowser.data.hls

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

class LocalHlsServer(
    private val preferredPort: Int = 13746
) {
    @Volatile
    private var server: BoundServer? = null

    val port: Int get() = server?.listeningPort ?: preferredPort

    @Synchronized
    fun start(root: File): Int {
        root.mkdirs()
        stop()
        var port = preferredPort
        var last: Exception? = null
        repeat(10) {
            try {
                val s = BoundServer(port, root)
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                Log.i(TAG, "HLS server started on $port -> ${root.absolutePath}")
                return port
            } catch (e: Exception) {
                last = e
                port++
            }
        }
        throw last ?: IllegalStateException("Unable to start HLS server")
    }

    @Synchronized
    fun stop() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
    }

    fun buildUrl(relativePath: String): String {
        val clean = relativePath.trimStart('/')
        return "http://127.0.0.1:$port/$clean"
    }

    fun hlsUrlForPackage(packageDir: File, root: File): String {
        val relative = packageDir.relativeTo(root).invariantSeparatorsPath + "/index.m3u8"
        return buildUrl(relative)
    }

    private class BoundServer(
        port: Int,
        private val root: File
    ) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri.substringBefore('?').trimStart('/')
            val file = File(root, uri)
            if (!file.canonicalPath.startsWith(root.canonicalPath) || !file.isFile) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
            val mime = when (file.extension.lowercase()) {
                "m3u8" -> "application/vnd.apple.mpegurl"
                "ts" -> "video/mp2t"
                "key" -> "application/octet-stream"
                "mp4" -> "video/mp4"
                else -> "application/octet-stream"
            }
            val fis = FileInputStream(file)
            return newFixedLengthResponse(Response.Status.OK, mime, fis, file.length()).also {
                it.addHeader("Access-Control-Allow-Origin", "*")
                it.addHeader("Accept-Ranges", "bytes")
            }
        }
    }

    companion object {
        private const val TAG = "LocalHlsServer"
    }
}
